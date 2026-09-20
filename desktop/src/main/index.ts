import { readFile, stat } from 'node:fs/promises'
import { basename, delimiter, join, resolve, sep } from 'node:path'
import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron'
import { writeFileSyncAtomic } from './atomic'
import { CoreClient, CoreRpcError } from './core'
import { HistoryStore } from './history'
import { checkBodyFiles, FileGrants, isRelativePath, storedPathFor } from './files'
import { finishImport, MAX_IMPORT_BYTES } from './importer'
import { NetworkStore } from './network'
import { OAuthTokenStore } from './oauth'
import { absorbCaptures, RuntimeStore } from './runtime'
import { SecretStore } from './secrets'
import {
  checkForUpdates,
  downloadUpdate,
  installUpdate,
  startUpdater,
  updateState
} from './updater'
import { Workspace } from './workspace'

const core = new CoreClient()
const workspace = new Workspace()
const secrets = new SecretStore()
const runtime = new RuntimeStore()
const grants = new FileGrants()
const history = new HistoryStore()
const network = new NetworkStore()
const oauthTokens = new OAuthTokenStore()
let mainWindow: BrowserWindow | null = null
/** Set when a close has been approved (renderer confirmed, or the updater is restarting). */
let closeApproved = false

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 900,
    minHeight: 600,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#0b0d10',
    webPreferences: {
      preload: join(__dirname, '../preload/index.cjs'),
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false
    }
  })

  // Renderer failures are invisible from the terminal otherwise; surface them while developing.
  if (!app.isPackaged) {
    mainWindow.webContents.on('console-message', (...args: any[]) => {
      const detail = args.find((arg) => arg && typeof arg === 'object' && 'message' in arg)
      const text = detail
        ? `${detail.message} (${detail.sourceId}:${detail.lineNumber})`
        : args.slice(1).join(' ')
      process.stderr.write(`[renderer] ${text}\n`)
    })
    mainWindow.webContents.on('did-fail-load', (_event, code, description, url) =>
      process.stderr.write(`[renderer] failed to load ${url}: ${description} (${code})\n`)
    )
    mainWindow.webContents.on('preload-error', (_event, path, error) =>
      process.stderr.write(`[renderer] preload ${path} failed: ${error.message}\n`)
    )
  }

  // Show only once painted, so startup never flashes an empty white frame.
  mainWindow.once('ready-to-show', () => mainWindow?.show())

  // Anything targeting a new window is an external link; hand it to the real browser.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    const safe = safeExternalUrl(url)
    if (safe) {
      void shell.openExternal(safe)
    }
    return { action: 'deny' }
  })

  // The window must never become a browser: a top-level navigation away from the shell
  // would carry the preload bridge to a remote page. External-looking URLs open in the
  // real browser instead, same rule as new windows.
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (isOwnUrl(url)) {
      return
    }
    event.preventDefault()
    const safe = safeExternalUrl(url)
    if (safe) {
      void shell.openExternal(safe)
    }
  })

  // Unsaved work lives in the renderer, so closing always asks it first. Already-approved
  // closes pass through without a second prompt.
  mainWindow.on('close', (event) => {
    if (closeApproved) {
      closeApproved = false
      return
    }
    event.preventDefault()
    mainWindow?.webContents.send('app:close-request')
  })

  const devServer = process.env.ELECTRON_RENDERER_URL
  if (devServer) {
    void mainWindow.loadURL(devServer)
  } else {
    void mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

function isOwnUrl(url: string): boolean {
  const devServer = process.env.ELECTRON_RENDERER_URL
  if (devServer && url.startsWith(devServer)) {
    return true
  }
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'file:' && parsed.pathname.endsWith('/renderer/index.html')
  } catch {
    return false
  }
}

/**
 * The file to import. `PING_IMPORT_FILE` stands in for the dialog so the smoke test can drive
 * the flow, like `PING_WORKSPACE` and `PING_FAKE_UPDATE`; a real user picks through the dialog.
 */
async function chooseImportFile(): Promise<string | null> {
  const override = process.env.PING_IMPORT_FILE
  if (override) {
    return override
  }
  const options: Electron.OpenDialogOptions = {
    properties: ['openFile'],
    filters: [
      { name: 'Postman, Insomnia or OpenAPI', extensions: ['json', 'yaml', 'yml'] }
    ]
  }
  const result = mainWindow
    ? await dialog.showOpenDialog(mainWindow, options)
    : await dialog.showOpenDialog(options)
  return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0]
}

/**
 * The file for an upload. `PING_UPLOAD_FILE` (a `path.delimiter` list, used in order) stands in for
 * the dialog so the smoke test can drive it, like `PING_IMPORT_FILE`.
 */
const uploadOverrides = (process.env.PING_UPLOAD_FILE ?? '').split(delimiter).filter(Boolean)

async function chooseUploadFile(): Promise<string | null> {
  if (uploadOverrides.length > 0) {
    return uploadOverrides.shift() ?? null
  }
  const options: Electron.OpenDialogOptions = { properties: ['openFile'] }
  const result = mainWindow
    ? await dialog.showOpenDialog(mainWindow, options)
    : await dialog.showOpenDialog(options)
  return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0]
}

/**
 * Files for a client certificate. `PING_CERT_FILE` (a `path.delimiter` list, used in order) stands
 * in for the dialogs so the smoke test can drive them, like `PING_UPLOAD_FILE`.
 */
const certOverrides = (process.env.PING_CERT_FILE ?? '').split(delimiter).filter(Boolean)

async function chooseCertFile(title: string): Promise<string | null> {
  if (certOverrides.length > 0) {
    return certOverrides.shift() ?? null
  }
  const options: Electron.OpenDialogOptions = { title, properties: ['openFile'] }
  const result = mainWindow
    ? await dialog.showOpenDialog(mainWindow, options)
    : await dialog.showOpenDialog(options)
  return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0]
}

function failure(code: number | null, message: string): { ok: false; error: { code: number | null; message: string } } {
  return { ok: false, error: { code, message } }
}

/** A save-dialog suggestion, sanitized: the name comes from the renderer, so it is untrusted. */
function safeDownloadName(value: string): string {
  const cleaned = value.replace(/[\\/:*?"<>|\r\n]+/g, ' ').trim()
  return cleaned.length > 0 ? cleaned.slice(0, 120) : 'response'
}

/**
 * Store and variable calls name a root, but the renderer does not get to choose it: the
 * shell owns the open folder. Injecting the root here means a compromised renderer cannot
 * read or write outside it, and the core checks the same boundary again.
 */
function withWorkspaceRoot(
  params: unknown,
  root: string
): { params: Record<string, unknown> } | { message: string } {
  const source = params && typeof params === 'object' ? (params as Record<string, unknown>) : {}
  const safe: Record<string, unknown> = { ...source, root }

  for (const field of ['path', 'collection', 'to']) {
    const value = safe[field]
    if (value === undefined) {
      continue
    }
    if (typeof value !== 'string' || !isRelativePath(value)) {
      return { message: `Unsafe ${field}: ${String(value)}` }
    }
  }
  return { params: safe }
}

/**
 * Per-request context the shell decides: where relative file paths resolve, and which cookie
 * jar scope the send uses.
 *
 * Relative file paths resolve against the request's collection. The renderer names the collection;
 * the shell turns it into a folder under the workspace root and overwrites anything the renderer
 * sent as `filesBase`, so a compromised renderer cannot point relative paths anywhere else.
 */
function withRequestContext(params: unknown): Record<string, unknown> {
  const {
    collection,
    environment,
    filesBase: _ignoredBase,
    cookieScope: _ignoredScope,
    ...rest
  } = (params ?? {}) as Record<string, unknown>
  const current = workspace.current()
  const validCollection =
    typeof collection === 'string' && collection && isRelativePath(collection) ? collection : ''
  const validEnvironment =
    typeof environment === 'string' && environment && isRelativePath(environment) ? environment : ''

  const context: Record<string, unknown> = { ...rest }
  if (current && validCollection) {
    context.filesBase = join(current.root, validCollection)
  }
  if (current) {
    // One jar scope per collection and environment, so switching environment switches session. A
    // scratch tab (no collection) gets its own bucket. Built here, never taken from the renderer.
    context.cookieScope = cookieScopeFor(current.root, validCollection, validEnvironment)
  }
  return context
}

/**
 * The user's proxy, injected into every call that leaves the machine. The renderer's own
 * `network` is discarded: a proxy sees all traffic and its credentials, so only the settings the
 * user saved in this app may choose one, never a collection or a compromised renderer.
 */
function withNetwork(params: unknown): Record<string, unknown> {
  const { network: _ignored, ...rest } = (params ?? {}) as Record<string, unknown>
  const configured = network.forCore()
  return configured ? { ...rest, network: configured } : rest
}

/** The scope for a renderer's collection and environment names, or null when no folder is open. */
function scopeFrom(collection: unknown, environment: unknown): string | null {
  const current = workspace.current()
  if (!current) {
    return null
  }
  const valid = (value: unknown): string =>
    typeof value === 'string' && value && isRelativePath(value) ? value : ''
  return cookieScopeFor(current.root, valid(collection), valid(environment))
}

/** The jar scope for a collection and environment; the core treats it as an opaque key. */
function cookieScopeFor(root: string, collection: string, environment: string): string {
  return [root, collection, environment].join('|')
}

/** Merges secret values into an outgoing request's variables; secrets always win. */
function withSecrets(params: unknown): Record<string, unknown> {
  const source = params && typeof params === 'object' ? (params as Record<string, unknown>) : {}
  const variables =
    source.variables && typeof source.variables === 'object' ? (source.variables as object) : {}
  // Runtime values (captured from responses) outrank the files' variables; a secret with the
  // same name still wins over both.
  return { ...source, variables: { ...variables, ...runtime.all(), ...secrets.all() } }
}

/**
 * Restores a stored access token for an authorization-code request. The key is built the
 * same way the core builds it, so a token survives an app restart.
 */
function withOAuthTokens(params: unknown): Record<string, unknown> {
  const source = params && typeof params === 'object' ? (params as Record<string, unknown>) : {}
  const auth =
    source.auth && typeof source.auth === 'object' ? (source.auth as Record<string, unknown>) : null
  if (!auth || auth.type !== 'oauth2-authorization-code') {
    return source
  }
  const tokens = oauthTokens.get(oauthKey(auth))
  if (!tokens) {
    return source
  }
  return {
    ...source,
    auth: {
      ...auth,
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      expiresAtMillis: tokens.expiresAtMillis
    }
  }
}

function oauthKey(auth: Record<string, unknown>): string {
  const text = (value: unknown): string => (typeof value === 'string' ? value : '')
  const scopes = text(auth.scopes).trim().replace(/[,\s]+/g, ' ')
  return `authorization-code|${text(auth.tokenUrl)}|${text(auth.clientId)}|${scopes}`
}

function authorizeUrl(value: unknown): string | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const url = (value as Record<string, unknown>).authorizeUrl
  return typeof url === 'string' ? safeExternalUrl(url) : null
}

/** Only http(s) may be handed to the OS; a crafted collection must not pick the handler. */
function safeExternalUrl(url: string): string | null {
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? url : null
  } catch {
    return null
  }
}

/** Just the parts the renderer needs; the access and refresh tokens stay in the shell. */
function authOutcome(params: unknown): Record<string, unknown> {
  const payload = params && typeof params === 'object' ? (params as Record<string, unknown>) : {}
  const outcome: Record<string, unknown> = {}
  if (typeof payload.flowId === 'string') {
    outcome.flowId = payload.flowId
  }
  if (typeof payload.error === 'string') {
    outcome.error = payload.error
  }
  return outcome
}

function storeOAuthTokens(params: unknown): void {
  const payload = params && typeof params === 'object' ? (params as Record<string, unknown>) : {}
  const key = typeof payload.grantKey === 'string' ? payload.grantKey : ''
  const accessToken = typeof payload.accessToken === 'string' ? payload.accessToken : ''
  if (!key || !accessToken) {
    return
  }
  oauthTokens.set(key, {
    accessToken,
    refreshToken: typeof payload.refreshToken === 'string' ? payload.refreshToken : undefined,
    expiresAtMillis:
      typeof payload.expiresAtMillis === 'number' ? payload.expiresAtMillis : undefined
  })
}

/**
 * The renderer never touches the core directly: it is sandboxed and has no process access.
 * Every call crosses this single choke point, which is also where argument validation and
 * secret resolution will live once auth lands.
 *
 * Errors travel as data rather than as a rejected promise. `ipcRenderer.invoke` prefixes a
 * rejection with "Error invoking remote method ..." and contextBridge strips custom
 * properties, so a thrown {@link CoreRpcError} would reach the renderer as an anonymous
 * string. Returning an envelope keeps the code intact; the renderer rethrows a typed error
 * once the value is back in its own realm.
 */
function registerIpc(): void {
  ipcMain.handle('workspace:current', () => workspace.current())
  ipcMain.handle('workspace:choose', async () => {
    const before = workspace.current()?.root
    const next = await workspace.choose()
    if (next?.root !== before) {
      // A token captured against one folder's API should not follow the user into another, and
      // neither should its session cookies.
      runtime.clear()
      void core.request('cookies.clearAll', {}).catch(() => undefined)
    }
    return next
  })

  // Opens a collection or folder in the OS file manager. The renderer sends a path relative
  // to the workspace; main resolves and re-checks it so it cannot reach outside the root.
  ipcMain.handle('workspace:open', async (_event, path: unknown) => {
    const current = workspace.current()
    if (!current) {
      throw new Error('No collection folder is open')
    }
    if (typeof path !== 'string' || !isRelativePath(path)) {
      throw new Error(`Unsafe path: ${String(path)}`)
    }
    const root = resolve(current.root)
    const target = resolve(root, path)
    if (target !== root && !target.startsWith(root + sep)) {
      throw new Error(`Unsafe path: ${path}`)
    }
    const error = await shell.openPath(target)
    if (error) {
      throw new Error(error)
    }
  })

  // Saves a response body to a file the user picks: bytes the renderer already holds,
  // written atomically so a crash cannot leave a half-written file.
  ipcMain.handle('response:save', async (_event, payload: unknown) => {
    const body = payload as { suggestedName?: unknown; text?: unknown; base64?: unknown } | null
    if (!body || typeof body !== 'object') {
      throw new Error('response:save requires a payload')
    }
    if (typeof body.text !== 'string' && typeof body.base64 !== 'string') {
      throw new Error('response:save requires text or base64 content')
    }
    const data =
      typeof body.text === 'string'
        ? body.text
        : Buffer.from(body.base64 as string, 'base64')
    const result = await dialog.showSaveDialog({
      defaultPath: safeDownloadName(
        typeof body.suggestedName === 'string' ? body.suggestedName : 'response'
      )
    })
    if (result.canceled || !result.filePath) {
      return null
    }
    writeFileSyncAtomic(result.filePath, data)
    return result.filePath
  })

  // Imports a Postman or Insomnia export, or an OpenAPI document, into a new collection folder. Everything that matters
  // stays in main: the file is chosen here and read here (the renderer never names a path),
  // the workspace root is injected here, and lifted credentials are stored here and stripped
  // from what the renderer gets back.
  ipcMain.handle('import:collection', async () => {
    const current = workspace.current()
    if (!current) {
      return failure(null, 'No collection folder is open')
    }
    try {
      const file = await chooseImportFile()
      if (!file) {
        return { ok: true, value: null }
      }
      const info = await stat(file)
      if (info.size > MAX_IMPORT_BYTES) {
        return failure(null, 'That file is too large to import')
      }
      const content = await readFile(file, 'utf8')
      await core.ready
      const result = await core.request('import.collection', { root: current.root, content })
      return { ok: true, value: finishImport(result, (name, value) => secrets.set(name, value)) }
    } catch (cause) {
      return failure(
        cause instanceof CoreRpcError ? cause.code : null,
        cause instanceof Error ? cause.message : String(cause)
      )
    }
  })

  // The updater runs in main and pushes its state; the renderer only asks for the next step.
  ipcMain.handle('updates:state', () => updateState())
  ipcMain.handle('updates:check', () => {
    checkForUpdates()
    return updateState()
  })
  ipcMain.handle('updates:download', () => {
    downloadUpdate()
    return updateState()
  })
  ipcMain.handle('updates:install', () => {
    // The renderer asks about unsaved work after installing, so the restart must not
    // surface a second confirmation while tearing the window down.
    closeApproved = true
    installUpdate()
    return updateState()
  })

  // The renderer confirmed closing; allow it through.
  ipcMain.handle('app:confirm-close', () => {
    closeApproved = true
    mainWindow?.close()
  })

  // Choosing a file for an upload. The dialog runs here, so every path the renderer stores came
  // from it: inside the collection it is stored relative, elsewhere it is stored absolute and
  // granted for this session.
  ipcMain.handle('file:pick', async (_event, collection: unknown) => {
    const current = workspace.current()
    const collectionDir =
      current && typeof collection === 'string' && collection && isRelativePath(collection)
        ? join(current.root, collection)
        : null
    const chosen = await chooseUploadFile()
    if (!chosen) {
      return null
    }
    const { stored, grant } = storedPathFor(chosen, collectionDir)
    if (grant) {
      grants.grant(chosen)
    }
    return { stored, name: basename(chosen), size: (await stat(chosen)).size }
  })

  // Network settings are the user's, app-global, and never part of a collection. The password is
  // write-only: `get` reports whether one is set, and only `withNetwork` ever reads it.
  ipcMain.handle('network:get', () => network.get())
  ipcMain.handle('network:set', (_event, update: unknown) => network.set(update))
  // The renderer says which host and format; the shell asks the user for the files, so a path never
  // comes from the renderer. Resolves with the settings unchanged when the dialog is dismissed.
  ipcMain.handle('network:addCert', async (_event, request: unknown) => {
    network.checkCert(request)
    const type = (request as { type?: unknown } | null)?.type
    const first = await chooseCertFile(
      type === 'pem' ? 'Choose the PEM certificate' : 'Choose the PKCS#12 bundle (.p12 or .pfx)'
    )
    if (!first) {
      return network.get()
    }
    let key: string | undefined
    if (type === 'pem') {
      const chosen = await chooseCertFile('Choose the private key (PKCS#8 PEM)')
      if (!chosen) {
        return network.get()
      }
      key = chosen
    }
    return network.addCert(request, { cert: first, key })
  })
  ipcMain.handle('network:removeCert', (_event, id: unknown) => network.removeCert(id))

  ipcMain.handle('secrets:list', () => secrets.names())
  // The cookie jar lives in the core, keyed by scope. The renderer names a collection and an
  // environment; the shell builds the scope. The core never returns a cookie's value.
  ipcMain.handle('cookies:list', async (_event, collection: unknown, environment: unknown) => {
    const scope = scopeFrom(collection, environment)
    if (!scope) {
      return []
    }
    await core.ready
    const result = (await core.request('cookies.list', { scope })) as { cookies?: unknown[] }
    return result.cookies ?? []
  })
  ipcMain.handle(
    'cookies:clear',
    async (_event, collection: unknown, environment: unknown, domain: unknown, name: unknown) => {
      const scope = scopeFrom(collection, environment)
      if (!scope) {
        return 0
      }
      await core.ready
      const result = (await core.request('cookies.clear', {
        scope,
        domain: typeof domain === 'string' ? domain : undefined,
        name: typeof name === 'string' ? name : undefined
      })) as { removed?: number }
      return result.removed ?? 0
    }
  )
  ipcMain.handle('runtime:list', () => runtime.names())
  ipcMain.handle('runtime:clear', () => runtime.clear())
  ipcMain.handle('secrets:set', (_event, name: unknown, value: unknown) => {
    if (typeof name !== 'string' || typeof value !== 'string') {
      throw new Error('secrets:set requires a name and a value')
    }
    secrets.set(name, value)
  })
  ipcMain.handle('secrets:delete', (_event, name: unknown) => {
    if (typeof name !== 'string') {
      throw new Error('secrets:delete requires a name')
    }
    secrets.delete(name)
  })

  // History is shell-local: the renderer records a finished exchange and reads the list
  // back, but the request payload is opaque here and is returned to the renderer unchanged.
  ipcMain.handle('history:list', () => history.list())
  ipcMain.handle('history:add', (_event, entry: unknown) => history.add(entry))
  ipcMain.handle('history:clear', () => {
    history.clear()
  })

  ipcMain.handle('core:request', async (_event, method: unknown, params: unknown) => {
    if (typeof method !== 'string') {
      return failure(null, 'core:request requires a method name')
    }

    // Writes new collections under the workspace root and returns credentials it lifted out of
    // the files. Only the `import:collection` handler may call it: it picks the file, injects the
    // root and keeps secret values away from the renderer, none of which this path would do.
    if (method === 'import.collection') {
      return failure(null, 'import.collection is only available through the import dialog')
    }

    let args = params
    if (method.startsWith('store.') || method.startsWith('vars.') || method.startsWith('run.')) {
      const current = workspace.current()
      if (!current) {
        return failure(null, 'No collection folder is open')
      }
      const checked = withWorkspaceRoot(params, current.root)
      if ('message' in checked) {
        return failure(null, checked.message)
      }
      args = checked.params
    }

    if (method === 'http.send' || method === 'auth.authorize' || method.startsWith('run.')) {
      args = withNetwork(args)
    }
    if (method === 'http.send' || method === 'auth.authorize') {
      // Secret values are merged last, so they win over anything the files resolved. The
      // renderer never sees them; it only ever sends the non-secret variable map.
      args = withSecrets(args)
    }
    if (method === 'http.send') {
      args = withOAuthTokens(args)

      // Files: an absolute path must be one a dialog chose this session, and a relative one
      // must stay inside the collection. The core never sees a path that fails this.
      const refused = checkBodyFiles(args, grants)
      if (refused) {
        return failure(null, refused)
      }
      args = withRequestContext(args)
    }
    if (method.startsWith('run.')) {
      // A collection is data from disk and possibly from someone else: it must not be able to make
      // the app upload an arbitrary absolute path, and the renderer cannot ask it to.
      args = { ...(args as Record<string, unknown>), allowAbsoluteFiles: false }
    }

    try {
      await core.ready
      const value = await core.request(method, args)
      if (method === 'auth.authorize') {
        const url = authorizeUrl(value)
        if (url) {
          void shell.openExternal(url)
        }
      }
      // Captured values are stored here and stripped: the renderer sees only names and hit/miss.
      return { ok: true, value: method === 'http.send' ? absorbCaptures(value, runtime) : value }
    } catch (cause) {
      return failure(
        cause instanceof CoreRpcError ? cause.code : null,
        cause instanceof Error ? cause.message : String(cause)
      )
    }
  })
}

/**
 * First run: hand the user a folder and a starter collection instead of an empty sidebar.
 * The core owns the YAML, so the shell only chooses the location and remembers it.
 */
async function seedWorkspace(): Promise<void> {
  const root = join(app.getPath('home'), 'Ping')
  try {
    await core.ready
    await core.request('store.scaffold', { root, collection: 'My Collection' })
    workspace.adopt(root)
  } catch (error) {
    // Not fatal: the sidebar still offers "Open a folder".
    process.stderr.write(`[workspace] could not create a starter collection: ${String(error)}\n`)
  }
}

app.whenReady().then(async () => {
  core.notifications((notification) => {
    if (notification.method === 'auth.completed') {
      // The shell owns token persistence; the core keeps its session cache. Only the
      // outcome is forwarded, so tokens never reach the least-trusted layer.
      storeOAuthTokens(notification.params)
      mainWindow?.webContents.send('core:notification', {
        method: notification.method,
        params: authOutcome(notification.params)
      })
      return
    }
    mainWindow?.webContents.send('core:notification', notification)
  })
  core.stateChanges((state) => mainWindow?.webContents.send('core:state', state))
  core.start()

  registerIpc()
  workspace.onChange(() => mainWindow?.webContents.send('store:changed'))
  await workspace.restore()
  if (!workspace.current()) {
    await seedWorkspace()
  }
  secrets.load()
  history.load()
  network.load()
  oauthTokens.load()
  createWindow()
  startUpdater((state) => mainWindow?.webContents.send('updates:state', state))

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

// Closing stdin is what tells the core to exit; without this it would outlive the UI.
app.on('will-quit', () => {
  workspace.dispose()
  core.stop()
})
