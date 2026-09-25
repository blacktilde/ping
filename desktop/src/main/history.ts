import { existsSync, readFileSync } from 'node:fs'
import { writeFileSyncAtomic } from './atomic'
import { join } from 'node:path'
import { app } from 'electron'
import type { HistoryEntry } from '../shared/history'
import { log } from './log'

/**
 * The requests that have been executed, most recent first.
 *
 * History is shell-local and not collection data, so it lives in `userData` rather than
 * in the open folder: it follows the user, not the workspace, and never lands in a
 * repository the user commits. It is plain JSON because nothing here is a secret — the
 * renderer blanks credential-bearing auth fields before recording.
 *
 * The file is small and rewritten whole on every change. History is capped, so it stays
 * that way; a streaming append log would be premature.
 */
export class HistoryStore {
  private entries: HistoryEntry[] = []

  /** The number of entries kept; older ones fall off the end. */
  private static readonly LIMIT = 100

  load(): void {
    if (!existsSync(this.file())) {
      return
    }
    try {
      const parsed = JSON.parse(readFileSync(this.file(), 'utf8')) as unknown
      if (Array.isArray(parsed)) {
        this.entries = parsed.filter(isEntry).slice(0, HistoryStore.LIMIT)
      }
    } catch (error) {
      log('history', `could not read the history store: ${String(error)}`)
    }
  }

  list(): HistoryEntry[] {
    return this.entries
  }

  /** Records a finished exchange, newest first, dropping the oldest past the cap. */
  add(entry: unknown): HistoryEntry[] {
    if (!isEntry(entry)) {
      throw new Error('history:add requires a history entry')
    }
    // A repeated id means a retry of the same request; keep the newest only.
    this.entries = [entry, ...this.entries.filter((existing) => existing.id !== entry.id)].slice(
      0,
      HistoryStore.LIMIT
    )
    this.persist()
    return this.entries
  }

  clear(): void {
    this.entries = []
    this.persist()
  }

  private persist(): void {
    try {
      writeFileSyncAtomic(this.file(), JSON.stringify(this.entries))
    } catch (error) {
      log('history', `could not write the history store: ${String(error)}`)
    }
  }

  private file(): string {
    return join(app.getPath('userData'), 'history.json')
  }
}

/**
 * Guards against a hand-edited or half-written file. The request payload is opaque to the
 * shell — it is handed straight back to the renderer — so only the fields the sidebar and
 * the list need are checked.
 */
function isEntry(value: unknown): value is HistoryEntry {
  if (!value || typeof value !== 'object') {
    return false
  }
  const entry = value as Record<string, unknown>
  return (
    typeof entry.id === 'string' &&
    typeof entry.at === 'number' &&
    typeof entry.name === 'string' &&
    typeof entry.method === 'string' &&
    typeof entry.url === 'string' &&
    (entry.status === null || typeof entry.status === 'number') &&
    (entry.durationMs === null || typeof entry.durationMs === 'number') &&
    (entry.outcome === 'ok' || entry.outcome === 'error' || entry.outcome === 'cancelled') &&
    'request' in entry
  )
}
