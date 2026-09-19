import { join } from 'node:path'
import { app, BrowserWindow, ipcMain, shell } from 'electron'
import { CoreClient, CoreRpcError } from './core'
import { Workspace } from './workspace'

const core = new CoreClient()
const workspace = new Workspace()
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
    void shell.openExternal(url)
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

    try {
      await core.ready
      return { ok: true, value: await core.request(method, args) }
    } catch (cause) {
      return failure(
        cause instanceof CoreRpcError ? cause.code : null,
        cause instanceof Error ? cause.message : String(cause)
      )
    }
  })
}

app.whenReady().then(async () => {
  core.notifications((notification) => {
    mainWindow?.webContents.send('core:notification', notification)
  })
  core.start()

  registerIpc()
  workspace.onChange(() => mainWindow?.webContents.send('store:changed'))
  await workspace.restore()
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
