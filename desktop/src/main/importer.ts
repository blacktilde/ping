/**
 * The shell's half of importing a collection file.
 *
 * The core parses the file and writes new collection folders under the workspace root. What
 * it hands back includes the credentials it lifted out of those files: the shell stores them
 * in `safeStorage` and removes them from what the renderer sees, so a secret value never
 * reaches the renderer and never stays in a collection file.
 */

import type { ImportReport } from '../shared/import'

/** Larger than any real export; refuses to read a file that could exhaust memory. */
export const MAX_IMPORT_BYTES = 32 * 1024 * 1024

interface CoreImportResult {
  collections?: ImportReport['collections']
  warnings?: string[]
  secrets?: { name?: unknown; value?: unknown }[]
}

/**
 * Stores every lifted secret, then returns the report without their values.
 *
 * A secret that cannot be stored is reported rather than swallowed: the file already refers
 * to it as `{{name}}`, so the user has to know the reference will not resolve.
 */
export function finishImport(
  result: unknown,
  storeSecret: (name: string, value: string) => void
): ImportReport {
  const value = (result ?? {}) as CoreImportResult
  const warnings = [...(value.warnings ?? [])]
  let stored = 0
  let failed = 0

  for (const secret of value.secrets ?? []) {
    if (typeof secret.name !== 'string' || typeof secret.value !== 'string') {
      failed++
      continue
    }
    try {
      storeSecret(secret.name, secret.value)
      stored++
    } catch {
      failed++
    }
  }
  if (failed > 0) {
    warnings.push(
      `${failed} credential${failed === 1 ? '' : 's'} could not be stored. ` +
        'The imported requests refer to them by name and will not resolve until you set them.'
    )
  }

  return { collections: value.collections ?? [], warnings, secretsStored: stored }
}
