/**
 * One executed request, as kept in the shell's history.
 *
 * History is shell-local, not collection data, so it never crosses to the core. It is
 * stored per user in `userData` and passed between the main process and the renderer
 * through the preload bridge, which is why the shape lives here rather than in the
 * renderer's `lib/`.
 *
 * `request` is the request as stored (a `StoredRequest`): the renderer builds it with the
 * same `draftToStored` it uses to save a file, so clicking an entry can restore the draft
 * through `storedToDraft`. Secret-bearing auth fields are blanked before recording, so a
 * literal credential typed into the editor is never written to history in the clear.
 */
export interface HistoryEntry {
  id: string
  /** Epoch milliseconds when the exchange finished. */
  at: number
  name: string
  method: string
  url: string
  /** The response status, or null when the exchange never completed. */
  status: number | null
  /** Total wall time in milliseconds, or null when it never completed. */
  durationMs: number | null
  outcome: 'ok' | 'error' | 'cancelled'
  request: unknown
}
