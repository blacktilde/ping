import { join } from 'node:path'
import { app, BrowserWindow, ipcMain, shell } from 'electron'
import { CoreClient, CoreRpcError } from './core'

const core = new CoreClient()
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
  ipcMain.handle('core:request', async (_event, method: unknown, params: unknown) => {
    if (typeof method !== 'string') {
      return { ok: false, error: { code: null, message: 'core:request requires a method name' } }
    }
    try {
      await core.ready
      return { ok: true, value: await core.request(method, params) }
    } catch (cause) {
      return {
        ok: false,
        error: {
          code: cause instanceof CoreRpcError ? cause.code : null,
          message: cause instanceof Error ? cause.message : String(cause)
        }
      }
    }
  })
}

app.whenReady().then(() => {
  core.notifications((notification) => {
    mainWindow?.webContents.send('core:notification', notification)
  })
  core.start()

  registerIpc()
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
