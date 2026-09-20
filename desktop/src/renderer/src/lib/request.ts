/**
 * The editable request model and the conversion to RPC params.
 *
 * Kept free of runes so it can be reasoned about — and type-checked — on its own. App state
 * holds a {@link RequestDraft}; `toRequestSpec` is the only place that decides what reaches
 * the core, which is where the contract's defaults live.
 */

import type {
  Assert,
  AssertType,
  Capture,
  CaptureSource,
  BodyMode,
  HttpMethod,
  HttpRequestSpec,
  Param,
  RequestBody,
  RequestDraft
} from './http'
import { authToSpec, newAuth } from './http'

export const METHODS: HttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']

export const BODY_MODES: { value: BodyMode; label: string }[] = [
  { value: 'none', label: 'None' },
  { value: 'json', label: 'JSON' },
  { value: 'raw', label: 'Raw' },
  { value: 'form', label: 'Form URL-encoded' },
  { value: 'multipart', label: 'Multipart' },
  { value: 'file', label: 'Binary file' }
]

export function emptyParam(): Param {
  return { name: '', value: '', enabled: true }
}

export const ASSERT_TYPES: { value: AssertType; label: string }[] = [
  { value: 'status', label: 'Status' },
  { value: 'header', label: 'Header' },
  { value: 'jsonpath', label: 'JSON path' },
  { value: 'body', label: 'Body' },
  { value: 'duration', label: 'Duration (ms)' }
]

/** Ops each type accepts, default first; the core rejects anything else. */
export const ASSERT_OPS: Record<AssertType, { value: NonNullable<Assert['op']>; label: string }[]> = {
  status: [{ value: 'equals', label: 'equals' }],
  header: [
    { value: 'exists', label: 'exists' },
    { value: 'equals', label: 'equals' },
    { value: 'contains', label: 'contains' }
  ],
  jsonpath: [
    { value: 'exists', label: 'exists' },
    { value: 'equals', label: 'equals' },
    { value: 'contains', label: 'contains' }
  ],
  body: [{ value: 'contains', label: 'contains' }],
  duration: [{ value: 'lt', label: 'is under' }]
}

/** Whether a type/op reads `target` and `expected`, so the editor can hide what is unused. */
export function assertUsesTarget(type: AssertType): boolean {
  return type === 'header' || type === 'jsonpath'
}

export function assertUsesExpected(type: AssertType, op: Assert['op']): boolean {
  return !(op === 'exists' && (type === 'header' || type === 'jsonpath'))
}

export function emptyAssert(): Assert {
  return { type: 'status', target: '', op: 'equals', expected: '200', enabled: true }
}

/**
 * Copies rows into plain objects with a fixed key order, so two drafts that mean the same
 * thing fingerprint the same. Fields the type does not read are dropped, which keeps a
 * stale target from surviving a switch from header to status.
 */
export function plainAsserts(items: Assert[] | undefined): Assert[] {
  return (items ?? []).map((item) => {
    const row: Assert = { type: item.type }
    if (assertUsesTarget(item.type)) row.target = item.target ?? ''
    if (item.op) row.op = item.op
    if (assertUsesExpected(item.type, item.op)) row.expected = item.expected ?? ''
    row.enabled = item.enabled ?? true
    return row
  })
}

export const CAPTURE_SOURCES: { value: CaptureSource; label: string }[] = [
  { value: 'jsonpath', label: 'JSON path' },
  { value: 'header', label: 'Header' },
  { value: 'status', label: 'Status' }
]

export function emptyCapture(): Capture {
  return { name: '', source: 'jsonpath', target: '', enabled: true }
}

/** Copies rows into plain objects with a fixed key order; a status capture has no target. */
export function plainCaptures(items: Capture[] | undefined): Capture[] {
  return (items ?? []).map((item) => {
    const row: Capture = { name: item.name ?? '', source: item.source }
    if (item.source !== 'status') row.target = item.target ?? ''
    row.enabled = item.enabled ?? true
    return row
  })
}

export function newDraft(): RequestDraft {
  return {
    name: 'Untitled request',
    method: 'GET',
    url: 'https://jsonplaceholder.typicode.com/todos/1',
    query: [],
    headers: [],
    body: { type: 'none', content: '', contentType: '', fields: [] },
    auth: newAuth(),
    asserts: [],
    capture: []
  }
}

/**
 * Copies rows into plain objects. The draft is a Svelte `$state` proxy, and a proxy cannot
 * be structured-cloned across the context bridge, so nothing reactive may reach the core.
 */
function plainParams(items: Param[] | undefined): Param[] {
  return (items ?? []).map(({ name, value, enabled, file, filename, contentType }) => {
    const row: Param = { name, value, enabled }
    // File fields only travel when set, so ordinary rows keep their three-field shape.
    if (file !== undefined) row.file = file
    if (filename) row.filename = filename
    if (contentType) row.contentType = contentType
    return row
  })
}

/**
 * Drops the fields a body mode does not use, so an unused textarea's contents cannot leak
 * into the payload after the user switches modes.
 */
export function bodyToSpec(body: RequestBody): HttpRequestSpec['body'] {
  switch (body.type) {
    case 'json':
      return { type: 'json', content: body.content }
    case 'raw': {
      const contentType = body.contentType.trim()
      return contentType
        ? { type: 'raw', content: body.content, contentType }
        : { type: 'raw', content: body.content }
    }
    case 'file': {
      const contentType = body.contentType.trim()
      return contentType
        ? { type: 'file', file: body.file ?? '', contentType }
        : { type: 'file', file: body.file ?? '' }
    }
    case 'form':
    case 'multipart':
      return { type: body.type, fields: plainParams(body.fields) }
    default:
      return { type: 'none' }
  }
}

export function toRequestSpec(draft: RequestDraft, requestId: string): HttpRequestSpec {
  const spec: HttpRequestSpec = {
    requestId,
    method: draft.method,
    url: draft.url,
    query: plainParams(draft.query),
    headers: plainParams(draft.headers),
    body: bodyToSpec(draft.body)
  }

  // Settings only travel when the file carried them; the engine owns the defaults.
  if (draft.timeoutMs != null) spec.timeoutMs = draft.timeoutMs
  if (draft.redirects != null) spec.redirects = draft.redirects
  if (draft.verifyTls != null) spec.verifyTls = draft.verifyTls
  if (draft.maxBodyBytes != null) spec.maxBodyBytes = draft.maxBodyBytes

  const auth = authToSpec(draft.auth)
  if (auth) spec.auth = auth
  const asserts = plainAsserts(draft.asserts)
  if (asserts.length > 0) spec.asserts = asserts
  const capture = plainCaptures(draft.capture).filter((item) => item.name.trim().length > 0)
  if (capture.length > 0) spec.capture = capture
  return spec
}

/** Rows that will actually reach the wire: enabled and named. Used for the tab badges. */
export function enabledCount(items: Param[]): number {
  return items.filter((item) => item.enabled && item.name.trim().length > 0).length
}

/** Assertions that will run: enabled ones. Used for the tab badge. */
export function assertCount(items: Assert[]): number {
  return items.filter((item) => item.enabled !== false).length
}

/** Captures that will run: enabled and named. Used for the tab badge. */
export function captureCount(items: Capture[]): number {
  return items.filter((item) => item.enabled !== false && item.name.trim().length > 0).length
}
