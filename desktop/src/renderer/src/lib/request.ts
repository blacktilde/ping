/**
 * The editable request model and the conversion to RPC params.
 *
 * Kept free of runes so it can be reasoned about — and type-checked — on its own. App state
 * holds a {@link RequestDraft}; `toRequestSpec` is the only place that decides what reaches
 * the core, which is where the contract's defaults live.
 */

import type {
  BodyMode,
  HttpMethod,
  HttpRequestSpec,
  Param,
  RequestBody,
  RequestDraft
} from './http'

export const METHODS: HttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']

export const BODY_MODES: { value: BodyMode; label: string }[] = [
  { value: 'none', label: 'None' },
  { value: 'json', label: 'JSON' },
  { value: 'raw', label: 'Raw' },
  { value: 'form', label: 'Form URL-encoded' },
  { value: 'multipart', label: 'Multipart' }
]

export function emptyParam(): Param {
  return { name: '', value: '', enabled: true }
}

export function newDraft(): RequestDraft {
  return {
    method: 'GET',
    url: 'https://jsonplaceholder.typicode.com/todos/1',
    query: [],
    headers: [],
    body: { type: 'none', content: '', contentType: '', fields: [] }
  }
}

/**
 * Copies rows into plain objects. The draft is a Svelte `$state` proxy, and a proxy cannot
 * be structured-cloned across the context bridge, so nothing reactive may reach the core.
 */
function plainParams(items: Param[] | undefined): Param[] {
  return (items ?? []).map(({ name, value, enabled }) => ({ name, value, enabled }))
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
    case 'form':
    case 'multipart':
      return { type: body.type, fields: plainParams(body.fields) }
    default:
      return { type: 'none' }
  }
}

export function toRequestSpec(draft: RequestDraft, requestId: string): HttpRequestSpec {
  return {
    requestId,
    method: draft.method,
    url: draft.url,
    query: plainParams(draft.query),
    headers: plainParams(draft.headers),
    body: bodyToSpec(draft.body)
  }
}

/** Rows that will actually reach the wire: enabled and named. Used for the tab badges. */
export function enabledCount(items: Param[]): number {
  return items.filter((item) => item.enabled && item.name.trim().length > 0).length
}
