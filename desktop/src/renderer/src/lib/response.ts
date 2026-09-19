/** Response-only derivations: cookie parsing and the pretty/raw body decision. */

import type { HttpHeader } from './http'

export interface Cookie {
  name: string
  value: string
  domain: string | null
  path: string | null
  expires: string | null
  maxAge: string | null
  sameSite: string | null
  httpOnly: boolean
  secure: boolean
}

/** The core reports each `Set-Cookie` as its own header, so repeated names are not merged. */
export function parseCookies(headers: HttpHeader[]): Cookie[] {
  return headers
    .filter((header) => header.name.toLowerCase() === 'set-cookie')
    .map(parseCookie)
}

function parseCookie(header: HttpHeader): Cookie {
  const [pair, ...attributes] = header.value.split(';')
  const separator = pair.indexOf('=')

  const cookie: Cookie = {
    name: separator === -1 ? pair.trim() : pair.slice(0, separator).trim(),
    value: separator === -1 ? '' : pair.slice(separator + 1).trim(),
    domain: null,
    path: null,
    expires: null,
    maxAge: null,
    sameSite: null,
    httpOnly: false,
    secure: false
  }

  for (const attribute of attributes) {
    const separator = attribute.indexOf('=')
    const key = (separator === -1 ? attribute : attribute.slice(0, separator)).trim().toLowerCase()
    const value = separator === -1 ? null : attribute.slice(separator + 1).trim()

    switch (key) {
      case 'domain':
        cookie.domain = value
        break
      case 'path':
        cookie.path = value
        break
      case 'expires':
        cookie.expires = value
        break
      case 'max-age':
        cookie.maxAge = value
        break
      case 'samesite':
        cookie.sameSite = value
        break
      case 'httponly':
        cookie.httpOnly = true
        break
      case 'secure':
        cookie.secure = true
        break
      default:
        break
    }
  }
  return cookie
}

/** @returns the indented form, or null when the payload is not JSON. */
export function prettyJson(content: string): string | null {
  try {
    return JSON.stringify(JSON.parse(content), null, 2)
  } catch {
    return null
  }
}

export function isHtml(contentType: string | null | undefined): boolean {
  return (contentType ?? '').toLowerCase().includes('text/html')
}
