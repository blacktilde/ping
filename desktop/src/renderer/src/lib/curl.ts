/**
 * Renders a draft request as a curl command.
 *
 * Pure string work with no runes or RPC, so the same result could later back a CLI export.
 * Known variables are substituted; a placeholder with no value is left in place rather than
 * dropped, so a secret resolved only in the shell stays a visible `{{name}}`.
 */

import type { AuthDraft, Param, RequestDraft } from './http'
import { bodyToSpec } from './request'

export interface CurlOptions {
  /** Resolved variable values. Secrets are not included; their placeholders survive. */
  variables?: Record<string, string>
}

type Resolve = (value: string) => string

/** Single-quotes a value for a POSIX shell, escaping embedded quotes. */
export function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}

function interpolate(value: string, variables: Record<string, string>): string {
  return value.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(variables, name) ? variables[name] : match
  )
}

/** Rows that reach the wire: enabled and named. Mirrors `enabledCount`. */
function enabled(items: Param[]): Param[] {
  return items.filter((item) => item.enabled && item.name.trim().length > 0)
}

export function toCurl(draft: RequestDraft, options: CurlOptions = {}): string {
  const variables = options.variables ?? {}
  const resolve: Resolve = (value) => interpolate(value, variables)

  const query = enabled(draft.query).map(
    (param) => `${resolve(param.name)}=${resolve(param.value)}`
  )
  // An API key in the query is part of the address, not a header; curl only sees the URL.
  if (draft.auth.type === 'api-key' && draft.auth.in === 'query' && draft.auth.key) {
    query.push(`${resolve(draft.auth.key)}=${resolve(draft.auth.value)}`)
  }

  const url = resolve(draft.url).trim()
  const target = query.length > 0 ? `${url}${url.includes('?') ? '&' : '?'}${query.join('&')}` : url

  const headers = enabled(draft.headers)
  const hasHeader = (name: string): boolean =>
    headers.some((header) => resolve(header.name).toLowerCase() === name.toLowerCase())

  const lines: string[] = [
    draft.method === 'HEAD'
      ? `curl --head ${shellQuote(target)}`
      : `curl -X ${draft.method} ${shellQuote(target)}`
  ]

  for (const header of headers) {
    lines.push(`-H ${shellQuote(`${resolve(header.name)}: ${resolve(header.value)}`)}`)
  }

  lines.push(...authLines(draft.auth, hasHeader, resolve), ...bodyLines(draft, hasHeader, resolve))

  if (draft.timeoutMs != null && draft.timeoutMs > 0) {
    lines.push(`--max-time ${draft.timeoutMs / 1000}`)
  }
  if (draft.redirects === 'normal' || draft.redirects === 'always') {
    // `always` keeps the method across 301/302/303, curling's default would downgrade to GET.
    lines.push(draft.redirects === 'always' ? '-L --post301 --post302 --post303' : '-L')
  }
  if (draft.verifyTls === false) {
    lines.push('-k')
  }

  return lines.join(' \\\n  ')
}

function authLines(
  auth: AuthDraft,
  hasHeader: (name: string) => boolean,
  resolve: Resolve
): string[] {
  switch (auth.type) {
    case 'basic':
      return auth.username || auth.password
        ? [`-u ${shellQuote(`${resolve(auth.username)}:${resolve(auth.password)}`)}`]
        : []
    case 'bearer':
      return auth.token && !hasHeader('Authorization')
        ? [`-H ${shellQuote(`Authorization: Bearer ${resolve(auth.token)}`)}`]
        : []
    case 'api-key':
      // A query key already made it into the URL; only the header form is left to add.
      return auth.key && auth.in !== 'query' && !hasHeader(resolve(auth.key))
        ? [`-H ${shellQuote(`${resolve(auth.key)}: ${resolve(auth.value)}`)}`]
        : []
    default:
      // OAuth2 needs a live token exchange, which a static command cannot express.
      return []
  }
}

function bodyLines(draft: RequestDraft, hasHeader: (name: string) => boolean, resolve: Resolve): string[] {
  const body = bodyToSpec(draft.body)
  const lines: string[] = []

  switch (body.type) {
    case 'json':
      if (!hasHeader('Content-Type')) {
        lines.push(`-H ${shellQuote('Content-Type: application/json')}`)
      }
      lines.push(`--data-raw ${shellQuote(resolve(body.content ?? ''))}`)
      break
    case 'raw':
      if (body.contentType && !hasHeader('Content-Type')) {
        lines.push(`-H ${shellQuote(`Content-Type: ${resolve(body.contentType)}`)}`)
      }
      lines.push(`--data-raw ${shellQuote(resolve(body.content ?? ''))}`)
      break
    case 'form':
      for (const field of enabled(body.fields ?? [])) {
        lines.push(`--data-urlencode ${shellQuote(`${resolve(field.name)}=${resolve(field.value)}`)}`)
      }
      break
    case 'multipart':
      for (const field of enabled(body.fields ?? [])) {
        lines.push(`-F ${shellQuote(`${resolve(field.name)}=${resolve(field.value)}`)}`)
      }
      break
    default:
      break
  }

  return lines
}
