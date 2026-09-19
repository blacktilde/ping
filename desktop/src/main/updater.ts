import { app } from 'electron'
import { autoUpdater } from 'electron-updater'

/**
 * Update checks are a packaged-build concern: a dev run never touches the network. The
 * publish config electron-builder writes into the app tells electron-updater where to look.
 */
export function startUpdater(): void {
  if (!app.isPackaged || process.env.PING_DISABLE_UPDATER === '1') {
    return
  }

  autoUpdater.autoDownload = true
  autoUpdater.on('error', (error) => process.stderr.write(`[updater] ${error.message}\n`))
  void autoUpdater.checkForUpdatesAndNotify()
}
