/**
 * Importing requests from other tools.
 *
 * Parsing lives in the core, so the future CLI reads the same formats. This is only the
 * renderer's view of it: a cheap check for "is this worth sending to the core", and the call.
 */

import { call, CoreError } from './core'
import type { ImportReport } from '../../../shared/import'
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

export type { ImportReport }

/**
 * Asks the shell to import a Postman or Insomnia file. The shell owns the file picker, the
 * workspace root and the secret store; this only carries the outcome back.
 *
 * @returns the report, or null when the user dismissed the picker
 */
export async function importCollectionFile(): Promise<ImportReport | null> {
  const result = await window.ping.importCollection()
  if (result.ok) {
    return result.value
  }
  throw new CoreError(result.error.code, result.error.message)
}
