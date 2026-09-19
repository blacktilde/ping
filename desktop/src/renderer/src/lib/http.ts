/**
 * The `http.send` shape as it crosses the RPC boundary.
 *
 * These mirror `contract/http.schema.json`. That schema is the source of truth; when it
 * moves, this file and the Java records move with it.
 */

import { call } from './core'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS'

export type BodyMode = 'none' | 'json' | 'raw' | 'form' | 'multipart'

export type RedirectPolicy = 'never' | 'normal' | 'always'

/** A name/value row the user can disable without deleting. Mirrors contract `param`. */
export interface Param {
  name: string
  value: string
  enabled: boolean
}

export interface RequestBody {
  type: BodyMode
  content: string
  /** Overrides the type implied by the mode; raw only in the UI today. */
  contentType: string
  fields: Param[]
}

/** The editable request, before it is turned into RPC params. */
export interface RequestDraft {
  name: string
  method: HttpMethod
  url: string
  query: Param[]
  headers: Param[]
  body: RequestBody
  timeoutMs?: number
  redirects?: RedirectPolicy
  verifyTls?: boolean
  maxBodyBytes?: number
}

/** Exactly the params `http.send` accepts. */
export interface HttpRequestSpec {
  requestId: string
  method: HttpMethod
  url: string
  query: Param[]
  headers: Param[]
  body: {
    type: BodyMode
    content?: string
    contentType?: string
    fields?: Param[]
  }
  timeoutMs?: number
  redirects?: RedirectPolicy
  verifyTls?: boolean
  maxBodyBytes?: number
}

export interface HttpHeader {
  name: string
  value: string
}

export interface HttpResponseBody {
  content?: string | null
  truncated: boolean
  bytes: number
  textual: boolean
  contentType?: string | null
  charset?: string
}

export interface HttpTiming {
  dnsMs?: number | null
  ttfbMs: number
  downloadMs: number
  totalMs: number
}

export interface RedirectHop {
  status: number
  url: string
  location?: string | null
}

export interface HttpResponse {
  status: number
  httpVersion: 'HTTP_1_1' | 'HTTP_2'
  headers: HttpHeader[]
  body: HttpResponseBody
  timing: HttpTiming
  redirects: RedirectHop[]
}

/**
 * Runs one exchange. The core blocks on the network for the length of the call, so the
 * renderer is free to keep painting; `requestId` is what makes the exchange cancellable.
 */
export async function sendRequest(spec: HttpRequestSpec): Promise<HttpResponse> {
  return call<HttpResponse>('http.send', spec)
}

/** @returns true when an exchange was in flight under this id and has been asked to stop */
export async function cancelRequest(requestId: string): Promise<boolean> {
  const result = await call<{ cancelled: boolean }>('http.cancel', { requestId })
  return result.cancelled
}
