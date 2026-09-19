import { app } from 'electron'
import { autoUpdater } from 'electron-updater'
import type { UpdateState } from '../shared/updates'

type Notify = (state: UpdateState) => void

/**
 * The updater is a small state machine the renderer reflects: it never downloads or installs
 * on its own. `autoDownload` stays false, so "available" means exactly that — the user is
 * asked before anything is fetched, and asked again before the app restarts.
 *
 * `PING_FAKE_UPDATE=1` runs the same state machine against timers instead of the network, so
 * the smoke test can drive the flow in an unpackaged build.
 */
type Status = UpdateState['status']

let status: Status = 'idle'
let version: string | null = null
let progress: UpdateState['progress'] = null
let error: string | null = null
let notify: Notify = () => {}
let started = false
let fake = false
let timer: NodeJS.Timeout | undefined

function enabled(): boolean {
  return fake || (app.isPackaged && process.env.PING_DISABLE_UPDATER !== '1')
}

function snapshot(): UpdateState {
  return { status, enabled: enabled(), version, progress, error }
}

function set(next: Partial<Omit<UpdateState, 'enabled'>>): void {
  if (next.status !== undefined) status = next.status
  if (next.version !== undefined) version = next.version
  if (next.progress !== undefined) progress = next.progress
  if (next.error !== undefined) error = next.error
  notify(snapshot())
}

export function updateState(): UpdateState {
  return snapshot()
}

export function checkForUpdates(): void {
  const busy = status === 'checking' || status === 'downloading'
  if (!enabled() || busy || status === 'downloaded' || status === 'installing') {
    return
  }
  set({ status: 'checking', error: null, progress: null })

  if (fake) {
    timer = setTimeout(() => set({ status: 'available', version: '99.0.0' }), 150)
    return
  }

  void autoUpdater.checkForUpdates().catch((cause) => {
    set({ status: 'error', error: cause instanceof Error ? cause.message : String(cause) })
  })
}

export function downloadUpdate(): void {
  if (!enabled() || status !== 'available') {
    return
  }
  set({ status: 'downloading', progress: { percent: 0, transferred: 0, total: 0, bytesPerSecond: 0 } })

  if (fake) {
    let percent = 0
    timer = setInterval(() => {
      percent = Math.min(100, percent + 25)
      if (percent >= 100) {
        clearInterval(timer)
        set({ status: 'downloaded', progress: null })
      } else {
        set({ progress: { percent, transferred: percent, total: 100, bytesPerSecond: 0 } })
      }
    }, 100)
    return
  }

  void autoUpdater.downloadUpdate().catch((cause) => {
    set({ status: 'error', error: cause instanceof Error ? cause.message : String(cause) })
  })
}

export function installUpdate(): void {
  if (!enabled() || status !== 'downloaded') {
    return
  }
  set({ status: 'installing' })
  if (fake) {
    return
  }
  autoUpdater.quitAndInstall()
}

export function startUpdater(publish: Notify): void {
  notify = publish
  fake = process.env.PING_FAKE_UPDATE === '1'
  if (started || !enabled()) {
    return
  }
  started = true
  notify(snapshot())

  // The fake drives itself through checkForUpdates/downloadUpdate, so it registers nothing.
  if (fake) {
    return
  }

  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = false
  autoUpdater.on('error', (cause) => set({ status: 'error', error: cause.message }))
  autoUpdater.on('update-available', (info) => set({ status: 'available', version: info.version }))
  autoUpdater.on('update-not-available', () => set({ status: 'up-to-date' }))
  autoUpdater.on('download-progress', (info) =>
    set({
      status: 'downloading',
      progress: {
        percent: info.percent,
        transferred: info.transferred,
        total: info.total,
        bytesPerSecond: info.bytesPerSecond
      }
    })
  )
  autoUpdater.on('update-downloaded', (info) =>
    set({ status: 'downloaded', version: info.version, progress: null })
  )

  // One check on launch; the user can ask again from the command palette.
  checkForUpdates()
}
