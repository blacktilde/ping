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

/**
 * A PDF the renderer can hand to Chromium's own viewer. The parameters after `;` are not
 * part of the type, and some servers still send the pre-registration `application/x-pdf`.
 */
export function isPdf(contentType: string | null | undefined): boolean {
  const type = (contentType ?? '').split(';')[0].trim().toLowerCase()
  return type === 'application/pdf' || type === 'application/x-pdf'
}

/**
 * The bytes behind a base64 body. A data: URL would carry the same payload as a string a
 * third longer than the file, so binary previews go through a Blob instead.
 */
export function bytesFromBase64(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

/** What the core keeps when a request names no cap of its own (`RequestSpec.DEFAULT_MAX_BODY_BYTES`). */
export const DEFAULT_DISPLAY_CAP = 10 * 1024 * 1024

/**
 * The highest cap the truncation banner offers. Past this a body stalls the stdio transport,
 * the context bridge and the editor; phase 22's spill-to-file is the way beyond it.
 */
export const MAX_OFFERED_CAP = 100 * 1024 * 1024

/**
 * The cap to resend with so a body of `total` bytes fits whole: the next power-of-two number
 * of megabytes, which leaves room for a body that grows a little between sends. Null when that
 * is past `MAX_OFFERED_CAP`, or when `total` already fits under `current`.
 */
export function raisedCap(total: number, current: number): number | null {
  if (!Number.isFinite(total) || total <= current) return null
  const mb = 1024 * 1024
  let cap = mb
  while (cap < total) cap *= 2
  // A power of two past the ceiling may still leave the ceiling itself big enough.
  if (cap > MAX_OFFERED_CAP) cap = MAX_OFFERED_CAP
  return cap >= total ? cap : null
}

/** Methods a resend cannot change anything with on the server, per RFC 9110. */
export function isSafeMethod(method: string): boolean {
  return ['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method.trim().toUpperCase())
}
