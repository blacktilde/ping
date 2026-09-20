/**
 * Executed-request history, held by the shell in `userData`.
 *
 * The list is loaded once at startup and kept in sync from the return value of every
 * mutation, so the renderer never has to poll. It is deliberately separate from the
 * collection store: history follows the user, not the open folder.
 */

import type { HistoryEntry } from '../../../shared/history'
import type { HttpResponse, RequestDraft } from './http'
import { draftToStored } from './store'

export const history = $state<{ entries: HistoryEntry[] }>({ entries: [] })

export async function loadHistory(): Promise<void> {
  history.entries = await window.ping.history.list()
}

export async function clearHistory(): Promise<void> {
  await window.ping.history.clear()
  history.entries = []
}

/** Fields that may carry a literal credential; blanked before an entry is persisted. */
const SECRET_FIELDS = ['password', 'token', 'value', 'clientSecret'] as const

/**
 * The request as stored, with credential-bearing auth fields emptied.
 *
 * A credential typed into the editor is either a `{{reference}}` (safe to keep) or a
 * literal the shell moves into `safeStorage` on save. History is written on every send,
 * before any save, so a literal must never land in the file: this leaves a reference
 * intact but drops a literal rather than recording it in the clear.
 */
function historyRequest(draft: RequestDraft): unknown {
  const stored = draftToStored(draft)
  if (stored.auth) {
    for (const field of SECRET_FIELDS) {
      const value = stored.auth[field]
      if (typeof value === 'string' && value.length > 0 && !value.includes('{{')) {
        delete stored.auth[field]
      }
    }
  }
  dropAbsoluteFiles(stored)
  return stored
}

/** True for a path that names a location by itself, on any platform. */
function isAbsolutePath(path: string): boolean {
  return path.startsWith('/') || path.startsWith('\\') || /^[a-zA-Z]:[\\/]/.test(path)
}

/**
 * An absolute file path is a personal directory layout, and it stops working anyway once the
 * session that chose it ends, so history keeps the file's role and drops the path. A path
 * relative to the collection is kept: it is part of the collection, not of this machine.
 */
function dropAbsoluteFiles(stored: ReturnType<typeof draftToStored>): void {
  const body = stored.body
  if (!body) {
    return
  }
  if (body.file && isAbsolutePath(body.file)) {
    delete body.file
  }
  for (const field of body.fields ?? []) {
    if (field.file && isAbsolutePath(field.file)) {
      field.file = ''
    }
  }
}

export interface HistoryInput {
  draft: RequestDraft
  response: HttpResponse | null
  outcome: HistoryEntry['outcome']
}

/** Records a finished exchange and adopts the returned list as the new state. */
export async function recordHistory({ draft, response, outcome }: HistoryInput): Promise<void> {
  const entry: HistoryEntry = {
    id: crypto.randomUUID(),
    at: Date.now(),
    name: draft.name || 'Untitled request',
    method: draft.method,
    url: draft.url,
    status: response?.status ?? null,
    durationMs: response?.timing.totalMs ?? null,
    outcome,
    request: historyRequest(draft)
  }
  history.entries = await window.ping.history.add(entry)
}
