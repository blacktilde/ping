import { watch, type FSWatcher } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { writeFileAtomic } from './atomic'
import { join } from 'node:path'
import { app, dialog } from 'electron'
import { log } from './log'

/**
 * Owns the folder the user opened: which one it is, when it changes on disk, and
 * remembering it between runs.
 *
 * The core does the YAML and the file IO; this only decides the root, hands it to the core
 * on every store call, and reports filesystem churn to the renderer. The renderer never
 * learns a path it did not get from here, and never gets one that escapes the root.
 */
export class Workspace {
  private root: string | null = null
  private watcher: FSWatcher | null = null
  private debounce: NodeJS.Timeout | null = null
  private listener: (() => void) | null = null

  onChange(listener: () => void): void {
    this.listener = listener
  }

  current(): { root: string } | null {
    return this.root ? { root: this.root } : null
  }

  /** Adopts a folder the shell chose itself, such as the first-run default. */
  adopt(root: string): void {
    this.setRoot(root, true)
  }

  async restore(): Promise<void> {
    // A test or a power user can point the app at a folder without a dialog.
    const override = process.env.PING_WORKSPACE
    if (override) {
      this.setRoot(override, false)
      return
    }

    try {
      const saved = JSON.parse(await readFile(this.statePath(), 'utf8')) as { root?: unknown }
      if (typeof saved.root === 'string' && saved.root) {
        this.setRoot(saved.root, false)
      }
    } catch {
      // No state file yet, or it is unreadable: start with no folder open.
    }
  }

  async choose(): Promise<{ root: string } | null> {
    const result = await dialog.showOpenDialog({ properties: ['openDirectory'] })
    if (result.canceled || result.filePaths.length === 0) {
      return this.current()
    }
    this.setRoot(result.filePaths[0], true)
    return this.current()
  }

  private setRoot(root: string, persist: boolean): void {
    this.root = root
    this.watch()

    if (persist) {
      void writeFileAtomic(this.statePath(), JSON.stringify({ root })).catch(() => {
        // Losing the remembered folder is not worth failing the open over.
      })
    }
  }

  private watch(): void {
    this.watcher?.close()
    this.watcher = null
    if (!this.root) {
      return
    }

    try {
      this.watcher = watch(this.root, { recursive: true }, () => {
        // A save is an atomic rename of a temp file into place, so every event here — even
        // the temp file's own — means a file under the root changed. The debounce coalesces
        // the create/write/rename/chmod burst into one refresh, so nothing needs filtering.
        this.notify()
      })
      // An unhandled `error` event throws — deleting the open folder, a renamed root or an
      // exhausted inotify limit would take down the whole main process, not just the watch.
      this.watcher.on('error', (error) => {
        log('workspace', `watch error on ${this.root}: ${String(error)}`)
      })
    } catch (error) {
      log('workspace', `cannot watch ${this.root}: ${String(error)}`)
    }
  }

  /** Stops watching the open folder; called on quit so the process can exit promptly. */
  dispose(): void {
    this.watcher?.close()
    this.watcher = null
    if (this.debounce) {
      clearTimeout(this.debounce)
      this.debounce = null
    }
  }

  /** Editors save in bursts; a short debounce turns a flurry of events into one refresh. */
  private notify(): void {
    if (this.debounce) {
      clearTimeout(this.debounce)
    }
    this.debounce = setTimeout(() => this.listener?.(), 150)
  }

  private statePath(): string {
    return join(app.getPath('userData'), 'workspace.json')
  }
}
