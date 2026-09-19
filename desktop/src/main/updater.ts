import { app, dialog } from 'electron'
import { autoUpdater } from 'electron-updater'

/**
 * Checks for updates in packaged builds only, and never downloads or installs without an
 * explicit yes. An update replaces the binary the app runs, so it stays the user's decision
 * rather than a background event.
 */
export function startUpdater(): void {
  if (!app.isPackaged || process.env.PING_DISABLE_UPDATER === '1') {
    return
  }

  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = false
  autoUpdater.on('error', (error) => process.stderr.write(`[updater] ${error.message}\n`))

  autoUpdater.on('update-available', (info) => {
    void dialog
      .showMessageBox({
        type: 'info',
        buttons: ['Download', 'Later'],
        defaultId: 0,
        cancelId: 1,
        message: `Ping ${info.version} is available`,
        detail: 'Download it now? The current version keeps working while it downloads.'
      })
      .then((choice) => {
        if (choice.response === 0) {
          void autoUpdater.downloadUpdate()
        }
      })
  })

  autoUpdater.on('update-downloaded', (info) => {
    void dialog
      .showMessageBox({
        type: 'info',
        buttons: ['Restart now', 'Later'],
        defaultId: 0,
        cancelId: 1,
        message: `Ping ${info.version} is ready to install`,
        detail: 'Restart to finish the update.'
      })
      .then((choice) => {
        if (choice.response === 0) {
          autoUpdater.quitAndInstall()
        }
      })
  })

  void autoUpdater.checkForUpdates().catch((error) => {
    process.stderr.write(`[updater] check failed: ${String(error)}\n`)
  })
}
