import { join } from 'node:path'
import { app, BrowserWindow, ipcMain, shell } from 'electron'
import { CoreClient, CoreRpcError } from './core'
import { OAuthTokenStore } from './oauth'
import { SecretStore } from './secrets'
import { Workspace } from './workspace'

const core = new CoreClient()
const workspace = new Workspace()
const secrets = new SecretStore()
const oauthTokens = new OAuthTokenStore()
let mainWindow: BrowserWindow | null = null

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

  const devServer = process.env.ELECTRON_RENDERER_URL
  if (devServer) {
    void mainWindow.loadURL(devServer)
  } else {
    void mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }
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

  ipcMain.handle('core:request', async (_event, method: unknown, params: unknown) => {
    if (typeof method !== 'string') {
      return failure(null, 'core:request requires a method name')
    }

    let args = params
    if (method.startsWith('store.') || method.startsWith('vars.')) {
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
  core.start()

  registerIpc()
  workspace.onChange(() => mainWindow?.webContents.send('store:changed'))
  await workspace.restore()
  if (!workspace.current()) {
    await seedWorkspace()
  }
  secrets.load()
  oauthTokens.load()
  createWindow()

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
app.on('will-quit', () => core.stop())
