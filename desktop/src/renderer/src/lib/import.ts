/**
 * Importing requests from other tools.
 *
 * Parsing lives in the core, so the future CLI reads the same formats. This is only the
 * renderer's view of it: a cheap check for "is this worth sending to the core", and the call.
 */

import { call } from './core'
import type { StoredRequest } from './store'

export interface ImportResult {
  request: StoredRequest
  /** Everything the source expressed that a request cannot; never silently dropped. */
  warnings: string[]
}

/**
 * True for pasted text that starts like a curl invocation. Deliberately strict, so an ordinary
 * URL or a sentence that happens to begin with "curl" is never intercepted; the core makes the
 * real decision and a failed import falls back to a plain paste.
 */
export function looksLikeCurl(text: string): boolean {
  return /^\s*(\$\s+)?curl(\.exe)?\s/i.test(text)
}

export async function importCurl(command: string): Promise<ImportResult> {
  const result = await call<{ request: StoredRequest; warnings?: string[] }>('import.curl', {
    command
  })
  return { request: result.request, warnings: result.warnings ?? [] }
}
