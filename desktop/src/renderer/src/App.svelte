<script lang="ts">
  import { call, CoreError, RpcError } from './lib/core'
  import {
    authToSpec,
    cancelRequest,
    sendRequest,
    type RequestDraft,
    type StreamChunk,
    type StreamStart
  } from './lib/http'
  import { isEventStream, SseParser } from './lib/sse'
  import { copyText } from './lib/clipboard'
  import { toCurl } from './lib/curl'
  import {
    importCollectionFile,
    importCurl,
    looksLikeCurl,
    type ImportReport
  } from './lib/import'
  import { checkForUpdates, loadUpdateState, updates, watchUpdates } from './lib/updates.svelte'
  import { clearHistory, history, loadHistory, recordHistory } from './lib/history.svelte'
  import { confirmDialog } from './lib/confirm.svelte'
  import { assertCount, captureCount, enabledCount, METHODS, toRequestSpec } from './lib/request'
  import { refreshRuntime } from './lib/runtime.svelte'
  import { openRun, run, watchRunProgress } from './lib/run.svelte'
  import { refreshCookies } from './lib/cookies.svelte'
  import {
    activeTab,
    activateTab,
    closeTab,
    closeTabsUnder,
    ensureTab,
    newTab,
    openTab,
    retargetTabs,
    tabs
  } from './lib/tabs.svelte'
  import {
    chooseWorkspace,
    createFolder,
    createRequest,
    currentWorkspace,
    deleteEntry,
    draftKey,
    draftToStored,
    duplicateEntry,
    moveEntry,
    onStoreChanged,
    openInFileManager,
    readRequest,
    renameEntry,
    scaffoldCollection,
    scanStore,
    storedToDraft,
    writeRequest,
    type StoreNode,
    type StoredRequest
  } from './lib/store'
  import {
    addEnvironment,
    clearVariables,
    loadCollection,
    loadEnvironment,
    persistVariables,
    variables,
    variablesReady
  } from './lib/vars.svelte'
  import { loadSecretRows, persistSecretRows } from './lib/secrets.svelte'
  import { setSecret } from './lib/secrets'
  import { cycleTheme, nextTheme, setTheme, THEMES } from './lib/theme.svelte'
  import KeyValueEditor from './components/KeyValueEditor.svelte'
  import BodyEditor from './components/BodyEditor.svelte'
  import AssertEditor from './components/AssertEditor.svelte'
  import AuthEditor from './components/AuthEditor.svelte'
  import CaptureEditor from './components/CaptureEditor.svelte'
  import DocsEditor from './components/DocsEditor.svelte'
  import RequestSettings from './components/RequestSettings.svelte'
  import ResponsePane from './components/ResponsePane.svelte'
  import Sidebar from './components/Sidebar.svelte'
  import VariablesPanel from './components/VariablesPanel.svelte'
  import RequestTabs from './components/RequestTabs.svelte'
  import Tabs from './components/Tabs.svelte'
  import SplitPane from './components/SplitPane.svelte'
  import CommandPalette from './components/CommandPalette.svelte'
  import ConfirmDialog from './components/ConfirmDialog.svelte'
  import NetworkSettings from './components/NetworkSettings.svelte'
  import RunPanel from './components/RunPanel.svelte'
  import UpdateBanner from './components/UpdateBanner.svelte'
  import appIcon from '../../../build/icon.png'
  import type { HistoryEntry } from '../../shared/history'

  interface CoreInfo {
    coreVersion: string
    javaVersion: string
    vendor: string
    nativeImage: boolean
  }

  // There is always at least one tab, so `active` is never undefined.
  ensureTab()

  let info = $state<CoreInfo | null>(null)
  let bootError = $state('')
  let coreState = $state<'down' | 'starting' | 'ready'>('ready')

  let nodes = $state<StoreNode[]>([])
  let storeError = $state('')
  let showVariables = $state(false)
  let showAbout = $state(false)
  let paletteOpen = $state(false)
  let showNetwork = $state(false)
  let workspaceRoot = $state<string | null>(null)
  let sidebarCollapsed = $state(readSidebarCollapsed())
  let sidebarPanel = $state<'collections' | 'history'>('collections')
  let curlStatus = $state('')
  let urlRequired = $state(false)
  let urlInput = $state<HTMLInputElement>()

  // The tab the editor is showing. Every per-request value lives on it, so switching tabs
  // swaps the whole editor and response state at once.
  const active = $derived(activeTab())

  const queryCount = $derived(enabledCount(active.draft.query))
  const headerCount = $derived(enabledCount(active.draft.headers))
  const assertTotal = $derived(assertCount(active.draft.asserts))
  const captureTotal = $derived(captureCount(active.draft.capture))
  const dirty = $derived(
    active.savedKey !== null && draftKey(active.draft) !== active.savedKey
  )
  // Installing restarts the app, so any tab with unsaved changes is at risk.
  const anyDirty = $derived(
    tabs.list.some((tab) => tab.savedKey !== null && draftKey(tab.draft) !== tab.savedKey)
  )
  // A feed is arriving: the head is in and the body is not finished. Stopping it keeps what came.
  const streaming = $derived(
    active.inFlight && active.response?.streamed === true && active.response.ended === undefined
  )
  // A collection is always the first path segment; requests can nest below it.
  const activeCollection = $derived(active.path ? active.path.split('/')[0] : '')

  // The exported command tracks the editor; a secret resolved only in the shell stays a
  // `{{name}}` placeholder because secret values never reach the renderer.
  const curlCommand = $derived(toCurl(active.draft, { variables: variables.resolved }))

  const requestTabs = $derived([
    { id: 'params', label: 'Params', badge: queryCount > 0 ? String(queryCount) : null },
    { id: 'headers', label: 'Headers', badge: headerCount > 0 ? String(headerCount) : null },
    { id: 'body', label: 'Body', badge: active.draft.body.type === 'none' ? null : '•' },
    { id: 'auth', label: 'Auth', badge: active.draft.auth.type === 'none' ? null : 'on' },
    { id: 'asserts', label: 'Asserts', badge: assertTotal > 0 ? String(assertTotal) : null },
    { id: 'capture', label: 'Capture', badge: captureTotal > 0 ? String(captureTotal) : null },
    { id: 'docs', label: 'Docs', badge: active.draft.docs?.trim() ? '•' : null },
    { id: 'settings', label: 'Settings', badge: null }
  ])

  // Proves the whole chain on startup: renderer, preload, main, core process.
  $effect(() => {
    call<CoreInfo>('core.info')
      .then((result) => (info = result))
      .catch((cause: Error) => (bootError = cause.message))
  })

  // Load the remembered folder once, then follow it on disk for the rest of the session.
  $effect(() => {
    void refresh({ autoOpen: true })
    return onStoreChanged(() => void refresh())
  })

  // History follows the user, not the open folder, so it loads once and is kept in sync by
  // the mutation calls themselves.
  $effect(() => {
    void loadHistory().catch((cause: Error) => (storeError = cause.message))
  })

  // The updater lives in the shell; the renderer reflects its state and follows changes.
  $effect(() => {
    void loadUpdateState().catch(() => {})
    return watchUpdates()
  })

  // Variables follow the collection of the active tab, not the workspace.
  $effect(() => {
    const collection = activeCollection
    if (!collection) {
      clearVariables()
      return
    }
    void loadCollection(collection).catch((cause: Error) => (storeError = cause.message))
  })

  // Secret names are global; refresh them whenever the variables panel opens.
  $effect(() => {
    if (showVariables) {
      void loadSecretRows().catch((cause: Error) => (storeError = cause.message))
      void refreshRuntime()
      void refreshCookies(variables.collection, variables.environment)
    }
  })

  // A feed (server-sent events, NDJSON) is shown as it arrives. The core announces it with its head,
  // then sends the body in chunks, both keyed by the request's id; the final `http.send` result
  // replaces what is built here. The tab is found by id, so a feed keeps filling the tab that
  // sent it while another is on screen.
  $effect(() => {
    return window.ping.onNotification((notification) => {
      if (notification.method === 'http.stream.start') {
        const start = notification.params as StreamStart
        const tab = tabs.list.find((candidate) => candidate.requestId === start.requestId)
        if (!tab) {
          return
        }
        tab.events = []
        tab.sse = isEventStream(start.contentType) ? new SseParser() : null
        tab.response = {
          status: start.status,
          httpVersion: start.httpVersion,
          origin: start.origin,
          headers: start.headers,
          body: {
            content: '',
            truncated: false,
            bytes: 0,
            textual: true,
            contentType: start.contentType,
            charset: 'UTF-8'
          },
          timing: {
            dnsMs: start.dnsMs ?? null,
            ttfbMs: start.ttfbMs,
            downloadMs: 0,
            totalMs: start.ttfbMs
          },
          redirects: [],
          streamed: true
        }
        return
      }
      if (notification.method === 'http.stream.chunk') {
        const chunk = notification.params as StreamChunk
        const tab = tabs.list.find((candidate) => candidate.requestId === chunk.requestId)
        if (!tab?.response?.streamed || tab.response.ended) {
          return
        }
        tab.response.body.content = (tab.response.body.content ?? '') + chunk.text
        tab.response.body.bytes = chunk.total
        tab.response.body.truncated = chunk.truncated
        tab.response.timing.downloadMs = chunk.atMs
        tab.response.timing.totalMs = tab.response.timing.ttfbMs + chunk.atMs
        if (tab.sse && chunk.text) {
          const events = tab.sse.push(chunk.text, chunk.atMs)
          if (events.length > 0) {
            tab.events.push(...events)
          }
        }
        return
      }
      if (notification.method !== 'auth.completed') {
        return
      }
      const params = notification.params as { error?: string } | null
      active.authStatus = params?.error
        ? `Authorization failed: ${params.error}`
        : 'Authorized'
    })
  })

  // A run reports each request as it finishes. The subscription lives here rather than in the
  // panel so closing the panel mid-run does not lose the rest of it.
  $effect(() => watchRunProgress())

  $effect(() => {
    return window.ping.onCoreState((state) => {
      coreState = state
    })
  })

  // Main intercepts every window close and asks here, so unsaved tabs can veto the exit.
  $effect(() => {
    return window.ping.onCloseRequest(() => {
      if (!anyDirty) {
        void window.ping.confirmClose()
        return
      }
      void confirmDialog('You have unsaved changes. Close anyway?', {
        confirmLabel: 'Close',
        destructive: true
      }).then((answer) => {
        if (answer) {
          void window.ping.confirmClose()
        }
      })
    })
  })

  async function refresh(options: { autoOpen?: boolean } = {}): Promise<void> {
    try {
      const workspace = await currentWorkspace()
      workspaceRoot = workspace?.root ?? null
      if (!workspace) {
        nodes = []
        return
      }
      nodes = await scanStore()
      if (options.autoOpen && !active.path) {
        const first = firstRequest(nodes)
        if (first) {
          await openRequest(first)
        }
      }
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function openFolder(): Promise<void> {
    try {
      const workspace = await chooseWorkspace()
      if (!workspace) {
        return
      }
      // A dismissed dialog returns the current folder; switching to the same folder is a
      // no-op, not a reason to throw tabs away.
      if (workspace.root === workspaceRoot) {
        return
      }
      // Tabs point at paths in the old workspace and cannot survive the switch, but the
      // unsaved edits in them are the user's to keep or discard.
      if (anyDirty) {
        const proceed = await confirmDialog(
          'Switching folders discards unsaved changes in all tabs. Continue?',
          { confirmLabel: 'Discard & switch', destructive: true }
        )
        if (!proceed) {
          return
        }
      }
      workspaceRoot = workspace.root
      closeAllTabs()
      nodes = await scanStore()
      const first = firstRequest(nodes)
      if (first) {
        await openRequest(first)
      }
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  // The only way to make a request saveable when the open folder has no collection yet.
  async function newCollection(name: string): Promise<void> {
    try {
      await scaffoldCollection(name)
      nodes = await scanStore()
      const first = firstRequest(nodes)
      if (first) {
        await openRequest(first)
      }
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function openLocation(node: StoreNode): Promise<void> {
    try {
      await openInFileManager(node.path)
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function deleteNode(node: StoreNode): Promise<void> {
    // Dirty tabs under the deleted path cannot be saved afterwards, so they get the
    // same veto a tab close gets.
    const doomed = tabs.list.filter(
      (tab) =>
        tab.savedKey !== null &&
        draftKey(tab.draft) !== tab.savedKey &&
        (tab.path === node.path || tab.path?.startsWith(node.path + '/'))
    )
    if (doomed.length > 0) {
      const proceed = await confirmDialog(
        `Deleting this discards unsaved changes in ${doomed.length === 1 ? 'a tab' : `${doomed.length} tabs`}. Continue?`,
        { confirmLabel: 'Discard & delete', destructive: true }
      )
      if (!proceed) {
        return
      }
    }
    try {
      await deleteEntry(node.path)
      // Any tab editing the deleted file (or something under it) has nothing left to save.
      closeTabsUnder(node.path)
      nodes = await scanStore()
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  /**
   * Renames a request or folder. Open tabs follow the new path before the tree is rescanned, so a
   * tab never points at a file that no longer exists, and a tab with unsaved edits keeps them.
   */
  async function renameNode(node: StoreNode, name: string): Promise<void> {
    try {
      const path = await renameEntry(node.path, name)
      retargetTabs(node.path, path, node.type === 'request' ? name : undefined)
      nodes = await scanStore()
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function moveNode(node: StoreNode, target: StoreNode): Promise<void> {
    try {
      const path = await moveEntry(node.path, target.path)
      retargetTabs(node.path, path)
      nodes = await scanStore()
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function duplicateNode(node: StoreNode): Promise<void> {
    try {
      const path = await duplicateEntry(node.path)
      nodes = await scanStore()
      const copy = findNode(nodes, path)
      if (copy && copy.type === 'request') {
        await openRequest(copy)
      }
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function createFolderIn(parent: string, name: string): Promise<void> {
    try {
      await createFolder(parent, name)
      nodes = await scanStore()
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  /** Empty notes delete the key, so the YAML and the dirty fingerprint stay clean. */
  function setDocs(value: string): void {
    if (value.trim() === '') {
      delete active.draft.docs
    } else {
      active.draft.docs = value
    }
  }

  async function openRequest(node: StoreNode): Promise<void> {
    try {
      const next = storedToDraft(await readRequest(node.path))
      openTab({ draft: next, path: node.path })
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  function selectNode(node: StoreNode): void {
    if (node.path === active.path) {
      return
    }
    void openRequest(node)
  }

  /**
   * Opens the run panel for a collection. The environment defaults to the one in the header
   * when the run is of the collection the open tab belongs to, and to none otherwise: the
   * header's choice says nothing about a collection the user is not editing.
   */
  function runCollection(node: StoreNode): void {
    const environment = node.path === activeCollection ? variables.environment : ''
    openRun(node.path, node.name, environment)
  }

  async function createIn(collectionPath: string): Promise<void> {
    try {
      const path = await createRequest(collectionPath, 'New request')
      nodes = await scanStore()
      const node = findNode(nodes, path)
      if (node) {
        await openRequest(node)
      }
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function save(): Promise<void> {
    if (!active.path) {
      return
    }
    try {
      await protectAuthSecrets(active)
      await writeRequest(active.path, draftToStored(active.draft))
      active.savedKey = draftKey(active.draft)
      // Rescan here rather than waiting for the file watcher to report the write we just
      // made: a rename changes the tree's label, and depending on a filesystem event to
      // show our own save is a race the watcher does not always win.
      nodes = await scanStore()
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  let curlStatusTimer: number | undefined

  /** What the last collection import created and could not carry over; stays until dismissed. */
  let importReport = $state<ImportReport | null>(null)

  /** Asks the shell for a Postman or Insomnia file and shows what came of it. */
  async function importCollection(): Promise<void> {
    try {
      const report = await importCollectionFile()
      if (!report) {
        return
      }
      nodes = await scanStore()
      storeError = ''
      importReport = report
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  /** What the last import did, shown under the URL bar while its tab is active. */
  let importNotice = $state<{
    tabId: string
    error: boolean
    text: string
    warnings: string[]
  } | null>(null)

  /**
   * Pasting a curl command into the URL bar imports it as a request. Anything else pastes as
   * usual, and so does a command the core cannot read, so nothing the user pasted is lost.
   */
  async function pasteIntoUrl(event: ClipboardEvent): Promise<void> {
    const text = event.clipboardData?.getData('text') ?? ''
    if (!looksLikeCurl(text)) {
      return
    }
    event.preventDefault()
    const input = event.currentTarget as HTMLInputElement
    const start = input.selectionStart ?? input.value.length
    const end = input.selectionEnd ?? start
    try {
      const { request, warnings } = await importCurl(text)
      // A pristine tab is reused and anything else gets a new one, so the user's own draft is
      // never overwritten by a paste.
      openTab({ draft: storedToDraft(request) })
      importNotice = { tabId: tabs.activeId, error: false, text: 'Imported from cURL', warnings }
    } catch (cause) {
      input.setRangeText(text, start, end, 'end')
      input.dispatchEvent(new Event('input', { bubbles: true }))
      const reason = cause instanceof Error ? cause.message : String(cause)
      importNotice = {
        tabId: tabs.activeId,
        error: true,
        text: `Pasted as text: could not import as cURL (${reason})`,
        warnings: []
      }
    }
  }

  /** Puts the generated curl on the clipboard and confirms it briefly. */
  async function copyAsCurl(): Promise<void> {
    try {
      await copyText(curlCommand)
      curlStatus = 'copied!'
    } catch {
      curlStatus = 'Could not copy'
    }
    window.clearTimeout(curlStatusTimer)
    curlStatusTimer = window.setTimeout(() => (curlStatus = ''), 2000)
  }

  /**
   * Reopens a request from history in a new tab. The entry carries the request as it was
   * sent, so the draft is restored directly and has no file behind it until the user saves
   * it into a collection.
   */
  function selectHistory(entry: HistoryEntry): void {
    openTab({ draft: storedToDraft(entry.request as StoredRequest) })
  }

  async function clearHistoryEntries(): Promise<void> {
    try {
      await clearHistory()
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  /** Closes a tab, cancelling its exchange and confirming before discarding unsaved work. */
  async function closeRequestTab(id: string): Promise<void> {
    const tab = tabs.list.find((candidate) => candidate.id === id)
    if (!tab) {
      return
    }
    const unsaved = tab.savedKey !== null && draftKey(tab.draft) !== tab.savedKey
    if (unsaved) {
      const discard = await confirmDialog('Discard unsaved changes?', {
        confirmLabel: 'Discard changes',
        destructive: true
      })
      if (!discard) {
        return
      }
    }
    if (tab.requestId) {
      void cancelRequest(tab.requestId).catch(() => {})
    }
    closeTab(id)
  }

  function closeAllTabs(): void {
    for (const tab of tabs.list) {
      if (tab.requestId) {
        void cancelRequest(tab.requestId).catch(() => {})
      }
    }
    tabs.list = []
    tabs.activeId = ''
    newTab()
  }

  const SECRET_FIELDS = ['password', 'token', 'value', 'clientSecret'] as const

  /**
   * Moves literal credentials out of the file. A secret-bearing auth field the user typed
   * into is stored in the shell and replaced with a {{name}} reference, so a collection
   * that is committed and shared never carries the value.
   */
  async function protectAuthSecrets(tab: (typeof tabs.list)[number]): Promise<void> {
    for (const field of SECRET_FIELDS) {
      const value = tab.draft.auth[field]
      if (!value || value.includes('{{')) {
        continue
      }
      const name = secretNameFor(field, tab)
      await setSecret(name, value)
      tab.draft.auth[field] = `{{${name}}}`
    }
  }

  function secretNameFor(field: string, tab: (typeof tabs.list)[number]): string {
    const seed = tab.path ?? tab.draft.name ?? 'request'
    return `auth-${field}-${shortHash(seed)}`
  }

  function shortHash(value: string): string {
    let hash = 0
    for (let index = 0; index < value.length; index++) {
      hash = (Math.imul(31, hash) + value.charCodeAt(index)) | 0
    }
    return (hash >>> 0).toString(16).padStart(8, '0')
  }

  async function authorize(): Promise<void> {
    try {
      active.authStatus = 'Waiting for the browser…'
      await call('auth.authorize', {
        auth: authToSpec(active.draft.auth),
        variables: { ...variables.resolved }
      })
    } catch (cause) {
      active.authStatus = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function onEnvironmentChange(path: string): Promise<void> {
    try {
      await loadEnvironment(path)
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  // Reports success so the panel only confirms a save that happened; a failure shows in
  // `storeError` instead.
  async function onSaveVariables(): Promise<boolean> {
    try {
      await persistVariables()
      await persistSecretRows()
      storeError = ''
      return true
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
      return false
    }
  }

  async function onAddEnvironment(name: string): Promise<void> {
    if (!variables.collection) {
      return
    }
    try {
      await addEnvironment(name)
      showVariables = true
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  // The keydown handler accepts Meta (macOS) or Ctrl (everything else), so the hint must
  // not lie about which one.
  const modKey = $derived(navigator.platform.toLowerCase().includes('mac') ? '⌘' : 'Ctrl+')

  function cycleTab(delta: number): void {
    const index = tabs.list.findIndex((tab) => tab.id === tabs.activeId)
    const next = tabs.list[(index + delta + tabs.list.length) % tabs.list.length]
    if (next) {
      activateTab(next.id)
    }
  }

  function focusUrl(): void {
    urlInput?.focus()
    urlInput?.select()
  }

  function onKeydown(event: KeyboardEvent): void {
    if (!event.metaKey && !event.ctrlKey) {
      return
    }
    const key = event.key.toLowerCase()
    if (key === 'k') {
      event.preventDefault()
      paletteOpen = !paletteOpen
    } else if (key === 's') {
      event.preventDefault()
      void save()
    } else if (key === 'b') {
      event.preventDefault()
      toggleSidebar()
    } else if (key === 't') {
      event.preventDefault()
      newTab()
    } else if (key === 'w') {
      event.preventDefault()
      closeRequestTab(active.id)
    } else if (key === 'l') {
      event.preventDefault()
      focusUrl()
    } else if (event.code === 'BracketRight') {
      event.preventDefault()
      cycleTab(1)
    } else if (event.code === 'BracketLeft') {
      event.preventDefault()
      cycleTab(-1)
    } else if (/^[1-9]$/.test(key)) {
      event.preventDefault()
      const target = tabs.list[Number(key) - 1]
      if (target) {
        activateTab(target.id)
      }
    } else if (event.key === 'Enter') {
      event.preventDefault()
      void send()
    }
  }

  function readSidebarCollapsed(): boolean {
    try {
      return localStorage.getItem('ping.sidebar.collapsed') === 'true'
    } catch {
      return false
    }
  }

  function toggleSidebar(): void {
    sidebarCollapsed = !sidebarCollapsed
    try {
      localStorage.setItem('ping.sidebar.collapsed', String(sidebarCollapsed))
    } catch {
      // A locked-down profile just means the choice is not remembered.
    }
  }

  function firstRequest(list: StoreNode[]): StoreNode | null {
    for (const node of list) {
      if (node.type === 'request') return node
      const nested = firstRequest(node.children ?? [])
      if (nested) return nested
    }
    return null
  }

  function findNode(list: StoreNode[], path: string): StoreNode | null {
    for (const node of list) {
      if (node.path === path) return node
      const nested = findNode(node.children ?? [], path)
      if (nested) return nested
    }
    return null
  }

  async function send(): Promise<void> {
    // Capture the tab: the user can switch tabs while this exchange is in flight, and the
    // response belongs to the tab that sent it, not whichever is on screen when it lands.
    const tab = active
    const target = tab.draft.url.trim()
    if (tab.inFlight) {
      return
    }
    // Otherwise Send would be a silent no-op; the cursor goes where the fix does.
    if (target.length === 0) {
      urlRequired = true
      urlInput?.focus()
      return
    }

    const requestId = crypto.randomUUID()
    tab.requestId = requestId
    tab.inFlight = true
    tab.error = ''
    tab.cancelled = false
    // Snapshot the draft now: the user can edit it while the request is in flight, and the
    // history entry should describe what was actually sent.
    const sent = $state.snapshot(tab.draft) as RequestDraft
    // The previous response stays on screen while the next is in flight, as Postman does.
    // The pane is aria-busy so the staleness is announced rather than hidden.

    try {
      // Opening a request in another collection loads that collection's variables in the
      // background. Send is reachable the moment the tab is, so wait: otherwise the request
      // goes out interpolated against the collection that was open before, or with its
      // `{{placeholders}}` intact.
      await variablesReady()
      const spec = toRequestSpec(tab.draft, requestId)
      if (Object.keys(variables.resolved).length > 0) {
        // A spread unwraps the reactive proxy, which cannot cross the context bridge.
        spec.variables = { ...variables.resolved }
      }
      // Relative file paths resolve against the request's collection; the shell turns the name
      // into a folder and ignores any base the renderer might invent.
      const collectionName = tab.path ? tab.path.split('/')[0] : ''
      if (collectionName) {
        spec.collection = collectionName
        // The cookie jar is scoped per collection and environment; the shell validates both.
        if (variables.collection === collectionName && variables.environment) {
          spec.environment = variables.environment
        }
      }
      tab.response = await sendRequest(spec)
      if (!tab.response.streamed) {
        // Events belong to a feed; a document that follows one must not keep showing them.
        tab.events = []
        tab.sse = null
      }
    } catch (cause) {
      tab.response = null
      if (cause instanceof CoreError && cause.code === RpcError.requestCancelled) {
        tab.cancelled = true
      } else {
        tab.error = cause instanceof Error ? cause.message : String(cause)
      }
    } finally {
      tab.inFlight = false
      tab.requestId = ''
      // A capture may have added runtime variables; the panel lists their names.
      void refreshRuntime()
      void refreshCookies(variables.collection, variables.environment)
      const outcome = tab.cancelled ? 'cancelled' : tab.error ? 'error' : 'ok'
      // History is a convenience; a write failure must not surface as a request failure.
      void recordHistory({ draft: sent, response: tab.response, outcome }).catch(() => {})
    }
  }

  async function cancel(): Promise<void> {
    if (!active.requestId) {
      return
    }
    try {
      await cancelRequest(active.requestId)
    } catch (cause) {
      active.error = cause instanceof Error ? cause.message : String(cause)
    }
  }

  const paletteCommands = $derived.by(() => {
    const commands: { id: string; label: string; hint?: string; run: () => void }[] = [
      { id: 'send', label: 'Send request', hint: `${modKey}↵`, run: () => void send() },
      { id: 'save', label: 'Save request', hint: `${modKey}S`, run: () => void save() },
      { id: 'curl', label: 'Copy as cURL', run: () => void copyAsCurl() },
      { id: 'new-tab', label: 'New request tab', hint: `${modKey}T`, run: newTab },
      { id: 'close-tab', label: 'Close request tab', hint: `${modKey}W`, run: () => closeRequestTab(active.id) },
      { id: 'next-tab', label: 'Next tab', hint: `${modKey}⇧]`, run: () => cycleTab(1) },
      { id: 'previous-tab', label: 'Previous tab', hint: `${modKey}⇧[`, run: () => cycleTab(-1) },
      { id: 'focus-url', label: 'Focus request URL', hint: `${modKey}L`, run: focusUrl },
      { id: 'open', label: 'Open folder…', run: () => void openFolder() },
      { id: 'import', label: 'Import collection…', run: () => void importCollection() },
      { id: 'network', label: 'Network settings…', run: () => (showNetwork = true) },
      {
        id: 'sidebar',
        label: sidebarCollapsed ? 'Show collections sidebar' : 'Hide collections sidebar',
        hint: `${modKey}B`,
        run: toggleSidebar
      },
      {
        id: 'history-panel',
        label: 'Show request history',
        run: () => {
          sidebarCollapsed = false
          sidebarPanel = 'history'
        }
      },
      {
        id: 'variables',
        label: 'Toggle variables panel',
        run: () => (showVariables = !showVariables)
      },
      {
        id: 'theme',
        label: `Theme: ${nextTheme()}`,
        run: cycleTheme
      },
      ...THEMES.map(({ name, label }) => ({
        id: `theme-${name}`,
        label: `Set theme: ${label}`,
        run: () => setTheme(name)
      })),
      { id: 'tab-params', label: 'Go to Params', run: () => (active.editorTab = 'params') },
      { id: 'tab-headers', label: 'Go to Headers', run: () => (active.editorTab = 'headers') },
      { id: 'tab-body', label: 'Go to Body', run: () => (active.editorTab = 'body') },
      { id: 'tab-auth', label: 'Go to Auth', run: () => (active.editorTab = 'auth') },
      { id: 'tab-asserts', label: 'Go to Asserts', run: () => (active.editorTab = 'asserts') },
      { id: 'tab-capture', label: 'Go to Capture', run: () => (active.editorTab = 'capture') },
      { id: 'tab-docs', label: 'Go to Docs', run: () => (active.editorTab = 'docs') },
      { id: 'env-none', label: 'Environment: none', run: () => void onEnvironmentChange('') }
    ]

    // Direct commands for the first handful of tabs; ⌘1–⌘9 already reach them.
    for (const [index, tab] of tabs.list.slice(0, 9).entries()) {
      commands.push({
        id: `switch-tab-${tab.id}`,
        label: `Go to tab: ${tab.draft.name || 'Untitled'}`,
        hint: `${modKey}${index + 1}`,
        run: () => activateTab(tab.id)
      })
    }

    if (updates.state.enabled) {
      commands.push({
        id: 'check-updates',
        label: 'Check for updates',
        run: () => void checkForUpdates()
      })
    }
    if (activeCollection) {
      commands.push({ id: 'new', label: 'New request', run: () => void createIn(activeCollection) })
      const collectionNode = findNode(nodes, activeCollection)
      commands.push({
        id: 'run-collection',
        label: `Run collection: ${collectionNode?.name ?? activeCollection}`,
        run: () =>
          runCollection(
            collectionNode ?? { name: activeCollection, path: activeCollection, type: 'collection' }
          )
      })
    }
    if (active.draft.auth.type === 'oauth2-authorization-code') {
      commands.push({ id: 'authorize', label: 'Authorize (OAuth2)', run: () => void authorize() })
    }
    for (const environment of variables.environments) {
      commands.push({
        id: `env-${environment.path}`,
        label: `Environment: ${environment.name}`,
        run: () => void onEnvironmentChange(environment.path)
      })
    }
    return commands
  })
</script>

<svelte:window onkeydown={onKeydown} />

<div class="relative flex h-full">
  {#snippet mainContent()}
    <main class="flex min-w-0 flex-1 flex-col gap-3 p-5">
      <header class="flex items-center justify-between gap-4 border-b border-line pb-3">
        <div class="flex items-center gap-2.5">
          <button
            type="button"
            onclick={toggleSidebar}
            aria-label={sidebarCollapsed ? 'Show collections sidebar' : 'Hide collections sidebar'}
            title={sidebarCollapsed
              ? `Show collections sidebar (${modKey}B)`
              : `Hide collections sidebar (${modKey}B)`}
            class="rounded-md p-1.5 text-fg-faint transition hover:bg-line/60 hover:text-fg"
          >
            <svg
              viewBox="0 0 24 24"
              class="h-4 w-4"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <rect x="3" y="4" width="18" height="16" rx="2" />
              {#if sidebarCollapsed}
                <line x1="9" y1="4" x2="9" y2="20" />
                <path d="M14 9l3 3-3 3" />
              {:else}
                <line x1="9" y1="4" x2="9" y2="20" />
              {/if}
            </svg>
          </button>
          <img src={appIcon} alt="" class="h-8 w-8 shrink-0 rounded-lg" />
          <div>
            <h1 class="text-xl font-semibold tracking-tight">Ping</h1>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <select
            value={variables.environment}
            onchange={(event) => void onEnvironmentChange(event.currentTarget.value)}
            aria-label="Environment"
            class="rounded-md border border-line bg-panel px-2 py-1 text-xs text-fg-muted
                   outline-none transition hover:text-fg focus:border-accent"
          >
            <option value="">No environment</option>
            {#each variables.environments as environment (environment.path)}
              <option value={environment.path}>{environment.name}</option>
            {/each}
          </select>
          <button
            type="button"
            onclick={() => (showVariables = !showVariables)}
            aria-pressed={showVariables}
            class="rounded-md px-2 py-1 text-xs text-fg-muted transition hover:bg-line/60
                   hover:text-fg"
          >
            Variables
          </button>

          {#if info}
            <div
              class="relative"
              onfocusout={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget as Node | null)) showAbout = false
              }}
            >
              <button
                type="button"
                onclick={() => (showAbout = !showAbout)}
                aria-expanded={showAbout}
                aria-label="About this build"
                title="About this build"
                class="rounded-md p-1.5 text-fg-faint transition hover:bg-line/60 hover:text-fg"
              >
                <svg
                  viewBox="0 0 24 24"
                  class="h-4 w-4"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 11v5M12 8h.01" />
                </svg>
              </button>
              {#if showAbout}
                <dl
                  data-role="about"
                  class="absolute right-0 top-full z-30 mt-1 w-52 space-y-1 rounded-lg border
                         border-line bg-panel p-3 text-xs text-fg-muted shadow-lg"
                >
                  <div class="flex justify-between"><dt class="text-fg-faint">core</dt><dd>{info.coreVersion}</dd></div>
                  <div class="flex justify-between"><dt class="text-fg-faint">java</dt><dd>{info.javaVersion}</dd></div>
                  <div class="flex justify-between">
                    <dt class="text-fg-faint">mode</dt>
                    <dd>{info.nativeImage ? 'native-image' : 'jvm'}</dd>
                  </div>
                </dl>
              {/if}
            </div>
          {:else if !bootError}
            <span class="ml-2 text-xs text-fg-faint">connecting to core…</span>
          {/if}
        </div>
      </header>

      <UpdateBanner hasUnsaved={anyDirty} />

    <RequestTabs
      tabs={tabs.list}
      activeId={tabs.activeId}
      onActivate={activateTab}
      onClose={closeRequestTab}
      onNew={newTab}
    />

    <div
      id="request-tabpanel"
      role="tabpanel"
      aria-labelledby={`request-tab-${tabs.activeId}`}
      class="flex min-h-0 flex-1 flex-col gap-3"
    >
      <form
        class="flex gap-2"
        onsubmit={(event) => {
          event.preventDefault()
          void send()
        }}
      >
        <select
          bind:value={active.draft.method}
          aria-label="HTTP method"
          class="rounded-lg border border-line bg-panel px-3 py-2.5 text-sm font-medium outline-none
                 transition focus:border-accent"
        >
          {#each METHODS as verb (verb)}
            <option value={verb}>{verb}</option>
          {/each}
        </select>

        <div class="relative flex-1">
          <input
            bind:this={urlInput}
            bind:value={active.draft.url}
            aria-label="Request URL"
            aria-invalid={urlRequired}
            spellcheck="false"
            autocomplete="off"
            placeholder="https://api.example.com/resource"
            oninput={() => (urlRequired = false)}
            onpaste={(event) => void pasteIntoUrl(event)}
            class="w-full rounded-lg border bg-panel py-2.5 pl-4 pr-16 font-mono
                   text-sm outline-none transition
                   {urlRequired ? 'border-warning' : 'border-line focus:border-accent'}"
          />
          {#if urlRequired}
            <span
              data-role="url-required"
              role="status"
              class="pointer-events-none absolute -top-7 left-0 whitespace-nowrap rounded-md
                     border border-warning-soft bg-panel px-2 py-1 text-xs text-warning"
            >
              Enter a URL to send
            </span>
          {/if}
          <div class="absolute inset-y-0 right-1.5 flex items-center gap-1">
            <div class="relative">
              <button
                data-role="copy-curl"
                data-curl={curlCommand}
                type="button"
                onclick={() => void copyAsCurl()}
                aria-label="Copy as cURL"
                title="Copy as cURL"
                class="rounded-md p-1.5 text-fg-faint transition hover:bg-line/60 hover:text-fg"
              >
                <svg
                  viewBox="0 0 24 24"
                  class="h-4 w-4"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <polyline points="16 18 22 12 16 6" />
                  <polyline points="8 6 2 12 8 18" />
                </svg>
              </button>
              {#if curlStatus}
                <span
                  role="status"
                  data-role="curl-status"
                  class="pointer-events-none absolute -top-8 right-0 whitespace-nowrap rounded-md
                         border border-line bg-panel px-2 py-1 text-xs text-fg-muted shadow-lg"
                >
                  {curlStatus}
                </span>
              {/if}
            </div>
            {#if dirty}
              <span
                data-role="dirty"
                title="Unsaved changes"
                class="h-2 w-2 shrink-0 rounded-full bg-accent"
              ></span>
            {/if}
            <button
              data-role="save"
              type="button"
              onclick={save}
              disabled={!active.path || !dirty}
              aria-label="Save request"
              title={active.path
                ? 'Save changes'
                : 'Open a request from a collection, or create a collection first'}
              class="rounded-md p-1.5 text-fg-faint transition hover:bg-line/60 hover:text-fg
                     disabled:opacity-30 disabled:hover:bg-transparent disabled:hover:text-fg-faint"
            >
              <svg
                viewBox="0 0 24 24"
                class="h-4 w-4"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
                <polyline points="17 21 17 13 7 13 7 21" />
                <polyline points="7 3 7 8 15 8" />
              </svg>
            </button>
          </div>
        </div>

        <button
          type="submit"
          disabled={active.inFlight}
          class="rounded-lg bg-accent px-6 py-2.5 text-sm font-medium text-white
                 transition hover:brightness-110 disabled:opacity-40"
        >
          {active.inFlight ? (streaming ? 'Streaming…' : 'Sending…') : 'Send'}
        </button>

        {#if active.inFlight}
          <button
            type="button"
            onclick={cancel}
            class="rounded-lg border border-line px-4 py-2.5 text-sm text-fg
                   transition hover:border-fg-muted"
          >
            {streaming ? 'Stop' : 'Cancel'}
          </button>
        {/if}
      </form>

      {#if importNotice && importNotice.tabId === tabs.activeId}
        <div
          data-role="import-notice"
          data-error={importNotice.error}
          role="status"
          class="flex items-start gap-3 rounded-lg border px-3 py-2 text-xs
                 {importNotice.error
            ? 'border-warning-soft bg-warning-soft text-warning'
            : 'border-line bg-panel text-fg-muted'}"
        >
          <div class="min-w-0 flex-1">
            <p class="font-medium">{importNotice.text}</p>
            {#if importNotice.warnings.length > 0}
              <ul class="mt-1 list-disc space-y-0.5 pl-4">
                {#each importNotice.warnings as warning, index (index)}
                  <li data-role="import-warning">{warning}</li>
                {/each}
              </ul>
            {/if}
          </div>
          <button
            type="button"
            onclick={() => (importNotice = null)}
            aria-label="Dismiss import notice"
            class="rounded-md px-1.5 text-lg leading-none text-fg-faint transition hover:text-fg"
          >
            ×
          </button>
        </div>
      {/if}

      {#if coreState !== 'ready'}
        <p
          data-role="core-state"
          role="status"
          class="rounded-lg border border-warning-soft bg-warning-soft px-4 py-3 text-sm text-warning"
        >
          {coreState === 'down'
            ? 'The core engine stopped and is being restarted — requests will fail until it is back.'
            : 'Reconnecting to the core engine…'}
        </p>
      {/if}

      {#if storeError}
        <p
          data-role="store-error"
          role="alert"
          class="rounded-lg border border-warning-soft bg-warning-soft px-4 py-3 text-sm text-warning"
        >
          {storeError}
        </p>
      {/if}

      {#if importReport}
        <div
          data-role="import-report"
          role="status"
          class="flex items-start gap-3 rounded-lg border border-line bg-panel px-4 py-3 text-sm text-fg-muted"
        >
          <div class="min-w-0 flex-1">
            <p class="font-medium text-fg">
              Imported {importReport.collections.length}
              {importReport.collections.length === 1 ? 'collection' : 'collections'}
            </p>
            <ul class="mt-1 space-y-0.5 text-xs">
              {#each importReport.collections as created (created.path)}
                <li data-role="import-collection">
                  {created.name}: {created.requests}
                  {created.requests === 1 ? 'request' : 'requests'}{created.environments > 0
                    ? `, ${created.environments} ${created.environments === 1 ? 'environment' : 'environments'}`
                    : ''}
                </li>
              {/each}
            </ul>
            {#if importReport.secretsStored > 0}
              <p data-role="import-secrets" class="mt-1 text-xs">
                {importReport.secretsStored}
                {importReport.secretsStored === 1 ? 'credential was' : 'credentials were'} moved into
                your secret store; the files only refer to them by name.
              </p>
            {/if}
            {#if importReport.warnings.length > 0}
              <p class="mt-2 text-xs font-medium text-warning">Not carried over</p>
              <ul class="mt-1 max-h-40 list-disc space-y-0.5 overflow-auto pl-4 text-xs text-warning">
                {#each importReport.warnings as warning, index (index)}
                  <li data-role="import-report-warning">{warning}</li>
                {/each}
              </ul>
            {/if}
          </div>
          <button
            type="button"
            onclick={() => (importReport = null)}
            aria-label="Dismiss import report"
            class="rounded-md px-1.5 text-lg leading-none text-fg-faint transition hover:text-fg"
          >
            ×
          </button>
        </div>
      {/if}

      {#if active.error || bootError}
        <p
          data-role="error"
          role="alert"
          class="rounded-lg border border-danger-soft bg-danger-soft px-4 py-3 text-sm text-danger"
        >
          {active.error || bootError}
        </p>
      {:else if active.cancelled}
        <p
          data-role="cancelled"
          class="rounded-lg border border-line bg-panel px-4 py-3 text-sm text-fg-muted"
        >
          Request cancelled.
        </p>
      {/if}

      <SplitPane storageKey="ping.split.request" label="Resize request and response">
        {#snippet first()}
          <section
            data-role="request"
            class="flex min-h-0 flex-col overflow-hidden rounded-lg border border-line bg-panel"
          >
            <Tabs tabs={requestTabs} bind:active={active.editorTab} idPrefix="request" />

            <div
              id="request-panel"
              role="tabpanel"
              aria-labelledby={`request-tab-${active.editorTab}`}
              class="min-h-0 flex-1"
            >
              {#if active.editorTab === 'params'}
                <KeyValueEditor
                  items={active.draft.query}
                  nameLabel="Query parameter"
                  valueLabel="Query value"
                  addLabel="Add parameter"
                  emptyText="No query parameters yet."
                />
              {:else if active.editorTab === 'headers'}
                <KeyValueEditor
                  items={active.draft.headers}
                  nameLabel="Header name"
                  valueLabel="Header value"
                  addLabel="Add header"
                  emptyText="No headers yet."
                />
              {:else if active.editorTab === 'body'}
                <BodyEditor body={active.draft.body} collection={activeCollection} />
              {:else if active.editorTab === 'auth'}
                <AuthEditor
                  auth={active.draft.auth}
                  status={active.authStatus}
                  onAuthorize={authorize}
                />
              {:else if active.editorTab === 'asserts'}
                <AssertEditor items={active.draft.asserts} />
              {:else if active.editorTab === 'capture'}
                <CaptureEditor items={active.draft.capture} />
              {:else if active.editorTab === 'docs'}
                {#key active.path ?? tabs.activeId}
                  <DocsEditor
                    value={active.draft.docs}
                    label="Request notes"
                    onChange={setDocs}
                  />
                {/key}
              {:else}
                <RequestSettings draft={active.draft} />
              {/if}
            </div>
          </section>
        {/snippet}

        {#snippet second()}
          <ResponsePane
            response={active.response}
            inFlight={active.inFlight}
            suggestedName={active.draft.name || 'response'}
            verifyTls={active.draft.verifyTls !== false}
            events={active.events}
          />
        {/snippet}
      </SplitPane>
    </div>
    </main>
  {/snippet}

  <!-- The variables panel docks on the right, the mirror of the sidebar. -->
  {#snippet workspace()}
    <SplitPane
      direction="horizontal"
      unit="pixels"
      anchor="end"
      collapsed={!showVariables}
      initial={448}
      min={320}
      max={720}
      storageKey="ping.split.variables"
      label="Resize variables"
    >
      {#snippet first()}
        {@render mainContent()}
      {/snippet}

      {#snippet second()}
        <VariablesPanel
          onClose={() => (showVariables = false)}
          onSave={onSaveVariables}
          onAddEnvironment={onAddEnvironment}
        />
      {/snippet}
    </SplitPane>
  {/snippet}

  <SplitPane
    direction="horizontal"
    unit="pixels"
    collapsed={sidebarCollapsed}
    storageKey="ping.split.sidebar"
    label="Resize sidebar"
  >
    {#snippet first()}
      <Sidebar
        {nodes}
        activePath={active.path}
        {workspaceRoot}
        history={history.entries}
        bind:panel={sidebarPanel}
        onSelect={selectNode}
        onCreate={createIn}
        onRun={runCollection}
        onDelete={deleteNode}
        onOpenLocation={openLocation}
        onRename={(node, name) => void renameNode(node, name)}
        onDuplicate={(node) => void duplicateNode(node)}
        onMove={(node, target) => void moveNode(node, target)}
        onCreateFolder={(parent, name) => void createFolderIn(parent, name)}
        onNewCollection={newCollection}
        onOpenFolder={openFolder}
        onImport={() => void importCollection()}
        onSelectHistory={selectHistory}
        onClearHistory={clearHistoryEntries}
      />
    {/snippet}

    {#snippet second()}
      {@render workspace()}
    {/snippet}
  </SplitPane>

  <CommandPalette bind:open={paletteOpen} commands={paletteCommands} />

  {#if showNetwork}
    <NetworkSettings onClose={() => (showNetwork = false)} />
  {/if}

  {#if run.open}
    <RunPanel />
  {/if}

  <ConfirmDialog />
</div>
