/**
 * One line of the shell's diagnostic log, as the Logs view shows it.
 *
 * `source` is the tag the line was written under (`core`, `network`, `secrets`, ...). The
 * text has already been redacted in the main process: known secret values and credential
 * headers are masked before a line is kept, written to disk or sent to the renderer.
 */
export interface LogEntry {
  /** Increases by one per line for the life of the process; the renderer dedupes on it. */
  seq: number
  /** Milliseconds since the epoch. */
  time: number
  source: string
  text: string
}
