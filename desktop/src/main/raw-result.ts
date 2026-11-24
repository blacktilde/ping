/**
 * Pure and free of Electron, so `npm test` can load it.
 *
 * A large core response used to be `JSON.parse`d in the main process only to be cloned across to
 * the renderer, which blocked every other IPC call and request for the length of the parse. Lines
 * past {@link RAW_THRESHOLD} can instead cross as text and be parsed in the renderer, as long as
 * the shell has nothing to do with their content.
 */

/** Lines shorter than this are parsed in main: the parse is cheap and keeps the common path. */
export const RAW_THRESHOLD = 64 * 1024

/**
 * The core writes a response as `{"jsonrpc":"2.0","id":N,"result":...}` (Jackson keeps the order
 * the keys were put in). Anything that does not look exactly like that is left to `JSON.parse`.
 */
const RESULT_PREFIX = /^\{"jsonrpc":"2\.0","id":(\d+),"result":/

export interface RawResult {
  id: number
  /** The JSON text of the `result` member, unparsed. */
  json: string
}

/** Splits a successful response line into its id and the raw text of its result, or null. */
export function peekResult(line: string): RawResult | null {
  if (line.length < RAW_THRESHOLD || line.charCodeAt(line.length - 1) !== 0x7d /* } */) {
    return null
  }
  const match = RESULT_PREFIX.exec(line.slice(0, 64))
  if (!match) {
    return null
  }
  return { id: Number(match[1]), json: line.slice(match[0].length, -1) }
}

const CAPTURED_KEY = '"captured":'

/**
 * Whether a result carries anything in a `captured` member. The shell strips captured values
 * before the renderer sees them, so such a result must be parsed in main.
 *
 * Inside a JSON string every quote is escaped, so `"captured":` unescaped can only be a key; a
 * response body that merely contains the word cannot trip it, and cannot hide a real one.
 * Anything but an empty list counts, including `null` and an unusual spelling, to stay safe.
 */
export function hasCapturedEntries(json: string): boolean {
  let at = json.indexOf(CAPTURED_KEY)
  while (at !== -1) {
    const after = at + CAPTURED_KEY.length
    if (!json.startsWith('[]', after)) {
      return true
    }
    at = json.indexOf(CAPTURED_KEY, after)
  }
  return false
}
