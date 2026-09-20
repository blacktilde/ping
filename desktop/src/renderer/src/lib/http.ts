/**
 * The `http.send` shape as it crosses the RPC boundary.
 *
 * These mirror `contract/http.schema.json`. That schema is the source of truth; when it
 * moves, this file and the Java records move with it.
 */

import { call } from './core'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS'

export type BodyMode = 'none' | 'json' | 'raw' | 'form' | 'multipart' | 'file'

export type RedirectPolicy = 'never' | 'normal' | 'always'

/** Pins the protocol: `2` means prefer HTTP/2, the server can still answer with 1.1. */
export type HttpVersionPin = '1.1' | '2'

export type AuthType =
  | 'none'
  | 'basic'
  | 'bearer'
  | 'api-key'
  | 'oauth2-client-credentials'
  | 'oauth2-authorization-code'

/** The editable auth config. Every field is a string so the editor can bind unconditionally. */
export interface AuthDraft {
  type: AuthType
  username: string
  password: string
  token: string
  key: string
  value: string
  in: 'header' | 'query'
  tokenUrl: string
  authUrl: string
  clientId: string
  clientSecret: string
  scopes: string
}

/** As it crosses the boundary; empty fields are dropped. */
export interface AuthSpec {
  type: AuthType
  username?: string
  password?: string
  token?: string
  key?: string
  value?: string
  in?: 'header' | 'query'
  tokenUrl?: string
  authUrl?: string
  clientId?: string
  clientSecret?: string
  scopes?: string
}

export function newAuth(): AuthDraft {
  return {
    type: 'none',
    username: '',
    password: '',
    token: '',
    key: '',
    value: '',
    in: 'header',
    tokenUrl: '',
    authUrl: '',
    clientId: '',
    clientSecret: '',
    scopes: ''
  }
}

export function normalizeAuth(auth: Partial<AuthDraft> | null | undefined): AuthDraft {
  return { ...newAuth(), ...(auth ?? {}) }
}

export function authToSpec(auth: AuthDraft): AuthSpec | undefined {
  if (auth.type === 'none') {
    return undefined
  }
  const spec: AuthSpec = { type: auth.type }
  const fields: (keyof AuthDraft)[] = [
    'username', 'password', 'token', 'key', 'value', 'in',
    'tokenUrl', 'authUrl', 'clientId', 'clientSecret', 'scopes'
  ]
  for (const field of fields) {
    const value = auth[field]
    if (typeof value === 'string' && value.length > 0) {
      ;(spec as unknown as Record<string, unknown>)[field] = value
    }
  }
  return spec
}

/** A name/value row the user can disable without deleting. Mirrors contract `param`. */
export interface Param {
  name: string
  value: string
  enabled: boolean
  /**
   * A multipart file part: the path to read, relative to the collection or (for a file elsewhere)
   * absolute. Always chosen through the file dialog, never typed.
   */
  file?: string
  /** What the part is called on the wire; defaults to the file's own name. */
  filename?: string
  contentType?: string
}

/** True for a multipart row that sends a file rather than text. */
export function isFileParam(param: Param): boolean {
  return param.file !== undefined || !!param.filename || !!param.contentType
}

export interface RequestBody {
  type: BodyMode
  content: string
  /** The file sent as the whole body, for the `file` mode. */
  file?: string
  /** Overrides the type implied by the mode; raw only in the UI today. */
  contentType: string
  fields: Param[]
}

export type AssertType = 'status' | 'header' | 'jsonpath' | 'body' | 'duration'
export type AssertOp = 'exists' | 'equals' | 'contains' | 'lt'

/** One declarative check on a response; mirrors `assert` in `contract/http.schema.json`. */
export interface Assert {
  type: AssertType
  /** Header name for `header`, a path for `jsonpath`; unused otherwise. */
  target?: string
  /** Omitted means the type's default op, which the core owns. */
  op?: AssertOp
  expected?: string
  enabled?: boolean
}

export type CaptureSource = 'jsonpath' | 'header' | 'status'

/** One value to keep from a response; mirrors `capture` in `contract/http.schema.json`. */
export interface Capture {
  /** The runtime variable to set. */
  name: string
  source: CaptureSource
  /** The path for `jsonpath`, the header name for `header`; unused for `status`. */
  target?: string
  enabled?: boolean
}

/**
 * What a capture did. There is no value here on purpose: the shell keeps captured values and
 * removes them before a response reaches the renderer, so nothing in the UI can hold one.
 */
export interface CaptureOutcome {
  name: string
  source: string
  target?: string | null
  found: boolean
  message?: string | null
}

export interface AssertionResult {
  type: string
  target?: string | null
  op: string
  expected?: string | null
  passed: boolean
  actual?: string | null
  message?: string | null
}

/** The editable request, before it is turned into RPC params. */
export interface RequestDraft {
  name: string
  method: HttpMethod
  url: string
  query: Param[]
  headers: Param[]
  body: RequestBody
  auth: AuthDraft
  asserts: Assert[]
  capture: Capture[]
  /** Notes kept with the request; carried through edit and save, no editor yet. */
  docs?: string
  timeoutMs?: number
  redirects?: RedirectPolicy
  verifyTls?: boolean
  /** False keeps the request out of the cookie jar; absent means the jar applies. */
  cookies?: boolean
  /** Absent lets the client choose. */
  httpVersion?: HttpVersionPin
  maxBodyBytes?: number
}

/** The params `http.send` accepts. Settings are optional; the core applies their defaults. */
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
    file?: string
  }
  /**
   * The request's collection folder (relative to the workspace). The shell turns it into the base
   * that relative file paths resolve against; the renderer cannot supply the base itself.
   */
  collection?: string
  timeoutMs?: number
  redirects?: RedirectPolicy
  verifyTls?: boolean
  cookies?: boolean
  httpVersion?: HttpVersionPin
  /**
   * The active environment (relative to the workspace). With `collection` it selects the cookie jar
   * scope; the shell validates both and builds the scope itself.
   */
  environment?: string
  maxBodyBytes?: number
  /** How to authenticate; values may reference variables, which secrets resolve into. */
  auth?: AuthSpec
  asserts?: Assert[]
  capture?: Capture[]
  /** Resolved values for {{name}} placeholders; resolution precedence lives in the core. */
  variables?: Record<string, string>
}

export interface HttpHeader {
  name: string
  value: string
}

export interface HttpResponseBody {
  content?: string | null
  base64?: string | null
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
  /** One per enabled assertion; absent or empty when the request has none. */
  assertions?: AssertionResult[]
  /** One per enabled capture; names and hit/miss only, never the captured value. */
  captured?: CaptureOutcome[]
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
