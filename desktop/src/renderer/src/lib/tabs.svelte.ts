/**
 * Open request tabs.
 *
 * Each tab owns a complete request editor state — the draft, the file it is bound to, its
 * last saved fingerprint, and the response of its own last exchange. This is what lets one
 * request stay in flight while another is edited: every field that used to be a single
 * module-level value now lives on the tab it belongs to.
 *
 * The list is deeply reactive (`$state`), so `tab.draft.query` and friends stay bindable by
 * the editor components exactly as the old singleton draft was. Tabs live only for the
 * session; the files they point at are the durable part.
 */

import type { HttpResponse, RequestDraft } from './http'
import { newDraft } from './request'
import { draftKey } from './store'

export interface RequestTab {
  id: string
  draft: RequestDraft
  /** The saved file this tab edits, or null for a scratch/history tab. */
  path: string | null
  /** Draft fingerprint at the last load or save; null until the tab is backed by a file. */
  savedKey: string | null
  response: HttpResponse | null
  error: string
  cancelled: boolean
  inFlight: boolean
  /** The in-flight request's id, so Cancel reaches the right exchange. */
  requestId: string
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
    error: '',
    cancelled: false,
    inFlight: false,
    requestId: '',
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
    return current
  }

  const tab = makeTab(init)
  tabs.list.push(tab)
  tabs.activeId = tab.id
  return tab
}

/** A fresh scratch tab, always added rather than reusing an existing one. */
export function newTab(): RequestTab {
  const tab = makeTab()
  tabs.list.push(tab)
  tabs.activeId = tab.id
  return tab
}

export function activateTab(id: string): void {
  if (tabs.list.some((tab) => tab.id === id)) {
    tabs.activeId = id
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
  return removed
}

export function closeTabsUnder(path: string): RequestTab[] {
  const doomed = tabs.list.filter(
    (tab) => tab.path === path || (tab.path?.startsWith(`${path}/`) ?? false)
  )
  return doomed.map((tab) => closeTab(tab.id)).filter((tab): tab is RequestTab => Boolean(tab))
}

/** Seeds the workspace with one empty tab so the editor is never blank. */
export function ensureTab(): void {
  if (tabs.list.length === 0) {
    newTab()
  }
}
