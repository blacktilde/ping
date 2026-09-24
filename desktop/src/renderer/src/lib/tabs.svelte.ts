/**
 * Open request tabs.
 *
 * Each tab owns a complete request editor state — the draft, the file it is bound to, its
 * last saved fingerprint, and the response of its own last exchange. This is what lets one
 * request stay in flight while another is edited: every field that used to be a single
 * module-level value now lives on the tab it belongs to.
 *
 * The list is deeply reactive (`$state`), so `tab.draft.query` and friends stay bindable by
 * the editor components exactly as the old singleton draft was. Open tabs and drafts
 * persist across restarts.
 */

import type { HttpResponse, RequestDraft } from './http'
import type { SseEvent, SseParser } from './sse'
import { newDraft } from './request'
import { draftKey } from './store'
import { DEFAULT_DISPLAY_CAP } from './response'

const STORAGE_KEY = 'ping.tabs'

export interface RequestTab {
  id: string
  draft: RequestDraft
  /** The saved file this tab edits, or null for a scratch/history tab. */
  path: string | null
  /** Draft fingerprint at the last load or save; null until the tab is backed by a file. */
  savedKey: string | null
  response: HttpResponse | null
  /** Events of a live or finished server-sent stream, in arrival order. Not persisted. */
  events: SseEvent[]
  /** Incremental parser feeding `events` while a stream is arriving. */
  sse: SseParser | null
  error: string
  cancelled: boolean
  inFlight: boolean
  /** The in-flight request's id, so Cancel reaches the right exchange. */
  requestId: string
  /** The display cap `response` was read under, so a truncated body can say how much it kept. */
  responseCap: number
  /** Which request editor pane is showing: params, headers, body or auth. */
  editorTab: string
  authStatus: string
}

export const tabs = $state<{ list: RequestTab[]; activeId: string }>({
  list: [],
  activeId: ''
})

/** A brand-new draft's fingerprint, so an untouched scratch tab can be recognised. */
const FRESH_KEY = draftKey(newDraft())

function makeTab(init: Partial<Pick<RequestTab, 'draft' | 'path' | 'savedKey'>> = {}): RequestTab {
  const draft = init.draft ?? newDraft()
  return {
    id: crypto.randomUUID(),
    draft,
    path: init.path ?? null,
    savedKey: init.savedKey ?? (init.path ? draftKey(draft) : null),
    response: null,
    events: [],
    sse: null,
    error: '',
    cancelled: false,
    inFlight: false,
    requestId: '',
    responseCap: DEFAULT_DISPLAY_CAP,
    editorTab: 'params',
    authStatus: ''
  }
}

export function activeTab(): RequestTab {
  return tabs.list.find((tab) => tab.id === tabs.activeId) ?? tabs.list[0]
}

export function tabByPath(path: string): RequestTab | undefined {
  return tabs.list.find((tab) => tab.path === path)
}

/** True for a scratch tab the user has neither loaded nor edited: safe to reuse. */
function pristine(tab: RequestTab): boolean {
  return tab.path === null && tab.savedKey === null && draftKey(tab.draft) === FRESH_KEY
}

/**
 * Opens a request in a tab, reusing the current one when it is still an untouched scratch
 * tab. A request already open is focused rather than duplicated, so clicking it twice does
 * not stack copies.
 */
export function openTab(init: Partial<Pick<RequestTab, 'draft' | 'path' | 'savedKey'>>): RequestTab {
  if (init.path) {
    const existing = tabByPath(init.path)
    if (existing) {
      tabs.activeId = existing.id
      return existing
    }
  }

  const current = activeTab()
  if (current && pristine(current)) {
    const reused = makeTab(init)
    // Keep the id so any component keyed on it is not needlessly remounted.
    reused.id = current.id
    Object.assign(current, reused)
    tabs.activeId = current.id
    persist()
    return current
  }

  const tab = makeTab(init)
  tabs.list.push(tab)
  tabs.activeId = tab.id
  persist()
  return tab
}

/** A fresh scratch tab, always added rather than reusing an existing one. */
export function newTab(): RequestTab {
  const tab = makeTab()
  tabs.list.push(tab)
  tabs.activeId = tab.id
  persist()
  return tab
}

export function activateTab(id: string): void {
  if (tabs.list.some((tab) => tab.id === id)) {
    tabs.activeId = id
    persist()
  }
}

/**
 * Closes a tab and focuses a neighbour. Closing the last tab leaves a fresh empty one, so
 * there is always somewhere to type. Returns the tab that was removed, for the caller to
 * cancel if it was mid-flight.
 */
export function closeTab(id: string): RequestTab | undefined {
  const index = tabs.list.findIndex((tab) => tab.id === id)
  if (index === -1) {
    return undefined
  }
  const [removed] = tabs.list.splice(index, 1)
  if (tabs.activeId === id) {
    const neighbour = tabs.list[index] ?? tabs.list[index - 1]
    tabs.activeId = neighbour ? neighbour.id : ''
  }
  if (tabs.list.length === 0) {
    newTab()
  }
  persist()
  return removed
}

/**
 * Points open tabs at a path that moved. A tab on the path itself or on anything under it (a
 * renamed or moved folder's descendants) follows it, so the next save writes to the file's new
 * home instead of resurrecting the old one.
 *
 * `savedKey` fingerprints the draft's content, not its location, so it is left alone and a dirty
 * tab stays dirty. Only a request's own display name is carried into both the draft and the
 * fingerprint, so the rename is not itself reported as an unsaved edit.
 *
 * @param newName the request's new display name, when the move was a rename of a request
 */
export function retargetTabs(oldPath: string, newPath: string, newName?: string): void {
  for (const tab of tabs.list) {
    if (tab.path === oldPath) {
      tab.path = newPath
      if (newName !== undefined) {
        tab.draft.name = newName
        if (tab.savedKey !== null) {
          try {
            tab.savedKey = JSON.stringify({ ...JSON.parse(tab.savedKey), name: newName })
          } catch {
            // A key that is not JSON cannot be edited; the tab simply reads as changed.
          }
        }
      }
    } else if (tab.path?.startsWith(`${oldPath}/`)) {
      tab.path = `${newPath}${tab.path.slice(oldPath.length)}`
    }
  }
  persist()
}

/**
 * Binds a scratch tab to the file a save just created for it. The draft is what was written,
 * so its fingerprint becomes the saved one and the tab stops reading as dirty.
 */
export function bindTab(tab: RequestTab, path: string): void {
  tab.path = path
  tab.savedKey = draftKey(tab.draft)
  persist()
}

export function closeTabsUnder(path: string): RequestTab[] {
  const doomed = tabs.list.filter(
    (tab) => tab.path === path || (tab.path?.startsWith(`${path}/`) ?? false)
  )
  return doomed.map((tab) => closeTab(tab.id)).filter((tab): tab is RequestTab => Boolean(tab))
}

/** Seeds the workspace with one empty tab so the editor is never blank. */
export function ensureTab(): void {
  if (!restore()) {
    newTab()
  }
  persist()
}

/**
 * Rebuilds the tab list saved by a previous session. Responses are not restored — they
 * are stale snapshots of an exchange by definition.
 *
 * @returns false when there was nothing usable to restore, so the caller seeds a fresh tab
 */
function restore(): boolean {
  let saved: unknown
  try {
    saved = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null')
  } catch {
    return false
  }
  if (!saved || typeof saved !== 'object') {
    return false
  }
  const { list, activeId } = saved as { list?: unknown; activeId?: unknown }
  if (!Array.isArray(list)) {
    return false
  }
  const restored: RequestTab[] = []
  for (const item of list.slice(0, MAX_RESTORED_TABS)) {
    const tab = revive(item)
    if (tab) {
      restored.push(tab)
    }
  }
  if (restored.length === 0) {
    return false
  }
  tabs.list = restored
  tabs.activeId = restored.some((tab) => tab.id === activeId)
    ? (activeId as string)
    : restored[0].id
  return true
}

const MAX_RESTORED_TABS = 20

function revive(item: unknown): RequestTab | undefined {
  if (!item || typeof item !== 'object') {
    return undefined
  }
  const record = item as { draft?: unknown; path?: unknown; id?: unknown }
  if (!record.draft || typeof record.draft !== 'object') {
    return undefined
  }
  const draft = record.draft as RequestDraft
  if (typeof draft.url !== 'string' || typeof draft.name !== 'string') {
    return undefined
  }
  // Drafts persisted before assertions existed have no list.
  draft.asserts = Array.isArray(draft.asserts) ? draft.asserts : []
  draft.capture = Array.isArray(draft.capture) ? draft.capture : []
  return {
    id: typeof record.id === 'string' && record.id ? record.id : crypto.randomUUID(),
    draft,
    path: typeof record.path === 'string' ? record.path : null,
    savedKey: record.path != null ? draftKey(draft) : null,
    response: null,
    events: [],
    sse: null,
    error: '',
    cancelled: false,
    inFlight: false,
    requestId: '',
    responseCap: DEFAULT_DISPLAY_CAP,
    editorTab: 'params',
    authStatus: ''
  }
}

/** Rebuilt tab state saved after every change; responses are left out on purpose. */
function persist(): void {
  const snapshot = {
    activeId: tabs.activeId,
    list: tabs.list.slice(0, MAX_RESTORED_TABS).map((tab) => ({
      id: tab.id,
      path: tab.path,
      draft: $state.snapshot(tab.draft)
    }))
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot))
  } catch {
    // Not worth failing the UI over: the worst case is starting with a clean slate.
  }
}
