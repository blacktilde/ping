/**
 * The update lifecycle as the renderer sees it.
 *
 * The shell owns the updater; the renderer only reflects a state it is sent and asks for
 * the next step. `enabled` is false in an unpackaged build or when the updater is disabled,
 * so the UI can hide a control that would do nothing.
 */

export type UpdateStatus =
  | 'idle'
  | 'checking'
  | 'available'
  | 'downloading'
  | 'downloaded'
  | 'installing'
  | 'up-to-date'
  | 'error'

export interface UpdateProgress {
  percent: number
  transferred: number
  total: number
  bytesPerSecond: number
}

export interface UpdateState {
  status: UpdateStatus
  /** Whether this build can update itself at all. */
  enabled: boolean
  /** The version being offered or installed, when known. */
  version: string | null
  progress: UpdateProgress | null
  error: string | null
}
