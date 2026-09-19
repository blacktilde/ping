/**
 * The `http.send` shape as it crosses the RPC boundary.
 *
 * These mirror `contract/http.schema.json`. That schema is the source of truth; when it
 * moves, this file and the Java records move with it.
 */

import { call } from './core'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS'

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
export async function sendRequest(
  url: string,
  method: HttpMethod,
  requestId: string
): Promise<HttpResponse> {
  return call<HttpResponse>('http.send', { requestId, method, url })
}

/** @returns true when an exchange was in flight under this id and has been asked to stop */
export async function cancelRequest(requestId: string): Promise<boolean> {
  const result = await call<{ cancelled: boolean }>('http.cancel', { requestId })
  return result.cancelled
}
