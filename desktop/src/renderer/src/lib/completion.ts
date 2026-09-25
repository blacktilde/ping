/**
 * `{{name}}` completion: finding the placeholder the cursor is in, the names that can fill
 * it, and the text once one is picked.
 *
 * Pure functions over strings, so the plain inputs and the CodeMirror body editor agree on
 * what counts as an open placeholder, and the rules are unit tested without a DOM.
 */

/** Where a name comes from. Listed in the order the send resolves them, strongest last. */
export type VariableSource = 'collection' | 'environment' | 'runtime' | 'secret'

export interface VariableOption {
  name: string
  source: VariableSource
  /**
   * The resolved value, for collection and environment variables only. Runtime and secret
   * values never reach the renderer, so those carry a name alone.
   */
  value?: string
}

/** An unclosed `{{` before the cursor: `from` is where the name starts, `to` the cursor. */
export interface Placeholder {
  from: number
  to: number
  query: string
}

/**
 * The tail of a name the cursor sits inside, replaced when a name is picked. Narrower than
 * what the core accepts, so picking in `{{ba|/users` keeps the path.
 */
const NAME_TAIL = /[\w.-]/

/**
 * The placeholder the cursor is typing, if any. `{{ tok` counts, since the core trims the
 * whitespace inside the braces; `{{a b` and `{{done}}` do not.
 */
export function openPlaceholder(text: string, cursor: number): Placeholder | null {
  const open = text.lastIndexOf('{{', cursor - 2)
  if (open < 0) {
    return null
  }
  const between = text.slice(open + 2, cursor)
  const match = /^\s*([^\s{}]*)$/.exec(between)
  if (!match) {
    return null
  }
  return { from: cursor - match[1].length, to: cursor, query: match[1] }
}

export interface VariableScope {
  /** Collection and environment values as the core resolved them. */
  resolved: Record<string, string>
  /** Names the active environment defines, to tell them from the collection's. */
  environment: string[]
  runtime: string[]
  secrets: string[]
}

/**
 * Every name a request can use, each labelled with the scope that wins it on the wire:
 * secrets over runtime captures over the environment over the collection. A name a stronger
 * scope shadows shows no value, since the value shown would not be the one sent.
 */
export function variableOptions(scope: VariableScope): VariableOption[] {
  const byName = new Map<string, VariableOption>()
  const environment = new Set(scope.environment)
  for (const [name, value] of Object.entries(scope.resolved)) {
    byName.set(name, { name, source: environment.has(name) ? 'environment' : 'collection', value })
  }
  for (const name of scope.runtime) {
    byName.set(name, { name, source: 'runtime' })
  }
  for (const name of scope.secrets) {
    byName.set(name, { name, source: 'secret' })
  }
  return [...byName.values()]
    .filter((option) => option.name.trim() !== '')
    .sort((a, b) => a.name.localeCompare(b.name))
}

/** Names matching what has been typed: prefix matches first, then the rest containing it. */
export function matchOptions(options: VariableOption[], query: string): VariableOption[] {
  const needle = query.toLowerCase()
  if (!needle) {
    return options
  }
  const prefix: VariableOption[] = []
  const inner: VariableOption[] = []
  for (const option of options) {
    const name = option.name.toLowerCase()
    if (name.startsWith(needle)) {
      prefix.push(option)
    } else if (name.includes(needle)) {
      inner.push(option)
    }
  }
  return [...prefix, ...inner]
}

export interface Completed {
  text: string
  /** Where the cursor goes: just past the closing braces. */
  cursor: number
}

/**
 * Puts `name` into the placeholder. The rest of a name the cursor sits inside is replaced
 * rather than kept, and closing braces already there (an editor that pairs brackets typed
 * them) are reused rather than doubled.
 */
export function applyCompletion(text: string, placeholder: Placeholder, name: string): Completed {
  let end = placeholder.to
  while (end < text.length && NAME_TAIL.test(text[end])) {
    end++
  }
  const close = /^\s*\}\}/.exec(text.slice(end))
  const before = text.slice(0, placeholder.from) + name
  if (close) {
    const after = text.slice(end + close[0].length)
    const inserted = before + text.slice(end, end + close[0].length)
    return { text: inserted + after, cursor: inserted.length }
  }
  return { text: `${before}}}${text.slice(end)}`, cursor: before.length + 2 }
}
