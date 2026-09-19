/**
 * The collection tree, as the shell and core present it.
 *
 * Types mirror `contract/store.schema.json`. Workspace calls go to the Electron main
 * process; store calls go through the core, which owns the YAML and the file IO.
 */

import { call } from './core'
import { authToSpec, normalizeAuth } from './http'
import type { AuthSpec, BodyMode, HttpMethod, Param, RedirectPolicy, RequestDraft } from './http'

export type NodeType = 'collection' | 'folder' | 'request'

export interface StoreNode {
  name: string
  path: string
  type: NodeType
  method?: string | null
  children?: StoreNode[]
}

/** A request as stored. Empty fields are omitted, so most are optional here too. */
export interface StoredRequest {
  name: string
  method?: string
  url?: string
  query?: Param[]
  headers?: Param[]
  body?: {
    type?: BodyMode
    content?: string | null
    contentType?: string | null
    fields?: Param[]
  }
  auth?: AuthSpec
  timeoutMs?: number
  redirects?: RedirectPolicy
  verifyTls?: boolean
  maxBodyBytes?: number
}

// --- workspace (owned by the Electron main process) --------------------------------------

export function currentWorkspace(): Promise<{ root: string } | null> {
  return window.ping.workspace()
}

export function chooseWorkspace(): Promise<{ root: string } | null> {
  return window.ping.chooseWorkspace()
}

export function onStoreChanged(listener: () => void): () => void {
  return window.ping.onStoreChanged(listener)
}

// --- store RPC -----------------------------------------------------------------------------

export async function scanStore(): Promise<StoreNode[]> {
  const result = await call<{ collections: StoreNode[] }>('store.scan', {})
  return result.collections
}

export async function readRequest(path: string): Promise<StoredRequest> {
  return call<StoredRequest>('store.read', { path })
}

export async function writeRequest(path: string, request: StoredRequest): Promise<void> {
  await call<{ path: string }>('store.write', { path, request })
}

export async function createRequest(collection: string, name: string): Promise<string> {
  const result = await call<{ path: string }>('store.create', { collection, name })
  return result.path
}

/**
 * Creates a collection folder with a starter request under the open workspace. The core
 * owns the layout; this just names it. Idempotent, so naming an existing collection is safe.
 */
export async function scaffoldCollection(name: string): Promise<void> {
  await call<{ collection: string }>('store.scaffold', { collection: name })
}

/** Deletes a request file, or a collection/folder and everything under it. */
export async function deleteEntry(path: string): Promise<void> {
  await call<Record<string, never>>('store.delete', { path })
}

/** Opens a collection or folder in the OS file manager. */
export function openInFileManager(path: string): Promise<void> {
  return window.ping.openInFileManager(path)
}

// --- draft mapping -------------------------------------------------------------------------

/** Fixed key order, so two drafts that mean the same thing fingerprint the same. */
function plainParams(items: Param[] | undefined): Param[] {
  return (items ?? []).map((param) => ({
    name: param.name ?? '',
    value: param.value ?? '',
    enabled: param.enabled ?? true
  }))
}

export function storedToDraft(stored: StoredRequest): RequestDraft {
  return {
    name: stored.name || 'Untitled request',
    method: (stored.method as HttpMethod) ?? 'GET',
    url: stored.url ?? '',
    query: plainParams(stored.query),
    headers: plainParams(stored.headers),
    body: {
      type: (stored.body?.type as BodyMode) ?? 'none',
      content: stored.body?.content ?? '',
      contentType: stored.body?.contentType ?? '',
      fields: plainParams(stored.body?.fields)
    },
    auth: normalizeAuth(stored.auth),
    timeoutMs: stored.timeoutMs,
    redirects: stored.redirects,
    verifyTls: stored.verifyTls,
    maxBodyBytes: stored.maxBodyBytes
  }
}

export function draftToStored(draft: RequestDraft): StoredRequest {
  return {
    name: draft.name,
    method: draft.method,
    url: draft.url,
    query: plainParams(draft.query),
    headers: plainParams(draft.headers),
    body: {
      type: draft.body.type,
      content: draft.body.content,
      contentType: draft.body.contentType || undefined,
      fields: plainParams(draft.body.fields)
    },
    auth: authToSpec(draft.auth),
    timeoutMs: draft.timeoutMs,
    redirects: draft.redirects,
    verifyTls: draft.verifyTls,
    maxBodyBytes: draft.maxBodyBytes
  }
}

/** Stable fingerprint of the editable draft, for the dirty check. */
export function draftKey(draft: RequestDraft): string {
  return JSON.stringify(draftToStored(draft))
}
