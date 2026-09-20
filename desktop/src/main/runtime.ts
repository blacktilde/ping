/**
 * Runtime variables: values captured from responses, kept for the session.
 *
 * Unlike secrets these are never written to disk, and they are cleared when the open folder
 * changes. They are still live data (usually a token), so they follow the secrets' rule: they
 * live here in the main process and are merged into outgoing requests here, and the renderer
 * only ever learns their names.
 */
export class RuntimeStore {
  private values = new Map<string, string>()

  set(name: string, value: string): void {
    this.values.set(name, value)
  }

  /** Every value, for merging into an outgoing request. Never sent to the renderer. */
  all(): Record<string, string> {
    return Object.fromEntries(this.values)
  }

  names(): string[] {
    return [...this.values.keys()].sort()
  }

  clear(): void {
    this.values.clear()
  }
}

interface CapturedEntry {
  name?: unknown
  found?: unknown
  value?: unknown
}

/**
 * Stores what an `http.send` result captured, and returns the result without the values.
 *
 * A miss (`found: false`) stores nothing, so the variable stays absent and a later
 * `{{name}}` is sent as written. The returned copy keeps each entry's name and outcome, which
 * is all the renderer is allowed to show.
 */
export function absorbCaptures(result: unknown, store: RuntimeStore): unknown {
  if (!result || typeof result !== 'object') {
    return result
  }
  const response = result as { captured?: unknown }
  if (!Array.isArray(response.captured)) {
    return result
  }

  const stripped = (response.captured as CapturedEntry[]).map((entry) => {
    if (entry.found === true && typeof entry.name === 'string' && typeof entry.value === 'string') {
      store.set(entry.name, entry.value)
    }
    const { value: _value, ...rest } = entry
    return rest
  })
  return { ...response, captured: stripped }
}
