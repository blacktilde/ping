/**
 * The secret rows the editor is working on.
 *
 * Values are never read back from the shell, so existing rows start blank; typing a value
 * overwrites it, and deleting a row removes it on save.
 */

import { deleteSecret, listSecrets, setSecret } from './secrets'

export const secretRows = $state<{ name: string; value: string }[]>([])

/** The stored names, as saved rather than as edited; `{{name}}` completion offers these. */
export const secretNames = $state<{ names: string[] }>({ names: [] })

let originalNames: string[] = []

export async function loadSecretRows(): Promise<void> {
  originalNames = await listSecrets()
  secretNames.names = originalNames
  secretRows.splice(0, secretRows.length, ...originalNames.map((name) => ({ name, value: '' })))
}

export async function persistSecretRows(): Promise<void> {
  const kept = new Set<string>()
  for (const row of secretRows) {
    const name = row.name.trim()
    if (!name) {
      continue
    }
    kept.add(name)
    if (row.value) {
      await setSecret(name, row.value)
    }
  }
  for (const name of originalNames) {
    if (!kept.has(name)) {
      await deleteSecret(name)
    }
  }
  await loadSecretRows()
}

/** Refreshes the names alone, leaving any rows being edited as they are. */
export async function refreshSecretNames(): Promise<void> {
  try {
    secretNames.names = await listSecrets()
  } catch {
    // Completion is a courtesy: a failure here only leaves the list short.
  }
}
