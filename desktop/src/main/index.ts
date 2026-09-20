import { readFile, stat } from 'node:fs/promises'
import { join, resolve, sep } from 'node:path'
import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron'
import { writeFileSyncAtomic } from './atomic'
import { CoreClient, CoreRpcError } from './core'
import { HistoryStore } from './history'
import { finishImport, MAX_IMPORT_BYTES } from './importer'
import { OAuthTokenStore } from './oauth'
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
const history = new HistoryStore()
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
    filters: [{ name: 'Postman or Insomnia export', extensions: ['json'] }]
  }
  const result = mainWindow
    ? await dialog.showOpenDialog(mainWindow, options)
    : await dialog.showOpenDialog(options)
  return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0]
}

function failure(code: number | null, message: string): { ok: false; error: { code: number | null; message: string } } {
  return { ok: false, error: { code, message } }
}

/** Rejects anything that is not a plain path inside the workspace. */
function isRelativePath(value: string): boolean {
  if (value.startsWith('/') || value.startsWith('\\') || /^[a-zA-Z]:[\\/]/.test(value)) {
    return false
  }
  return !value.split(/[\\/]/).includes('..')
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

  for (const field of ['path', 'collection']) {
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

/** Merges secret values into an outgoing request's variables; secrets always win. */
function withSecrets(params: unknown): Record<string, unknown> {
  const source = params && typeof params === 'object' ? (params as Record<string, unknown>) : {}
  const variables =
    source.variables && typeof source.variables === 'object' ? (source.variables as object) : {}
  return { ...source, variables: { ...variables, ...secrets.all() } }
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
  ipcMain.handle('workspace:choose', () => workspace.choose())

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

  // Imports a Postman or Insomnia export into a new collection folder. Everything that matters
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

  ipcMain.handle('secrets:list', () => secrets.names())
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

    if (method === 'http.send' || method === 'auth.authorize') {
      // Secret values are merged last, so they win over anything the files resolved. The
      // renderer never sees them; it only ever sends the non-secret variable map.
      args = withSecrets(args)
    }
    if (method === 'http.send') {
      args = withOAuthTokens(args)
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
      return { ok: true, value }
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
