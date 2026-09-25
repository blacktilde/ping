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
  import { methodTone } from './lib/format'
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
  import { DEFAULT_DISPLAY_CAP, isSafeMethod } from './lib/response'
  import {
    activeTab,
    activateTab,
    bindTab,
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
    renameEnvironment,
    deleteEnvironment,
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
  import SaveRequestDialog from './components/SaveRequestDialog.svelte'
  import NetworkSettings from './components/NetworkSettings.svelte'
  import LogsPanel from './components/LogsPanel.svelte'
  import RunPanel from './components/RunPanel.svelte'
  import UpdateBadge from './components/UpdateBadge.svelte'
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
  let showLogs = $state(false)
  let workspaceRoot = $state<string | null>(null)
  let sidebarCollapsed = $state(readSidebarCollapsed())
  let editorCollapsed = $state(readEditorCollapsed())
  /** Open while a scratch tab is being given a home; false the rest of the time. */
  let choosingSaveTarget = $state(false)
  /** The collection the last save-as picked, so a run of saves starts where the last one went. */
  let lastSaveTarget = $state<string | null>(null)
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
  // A tab with no file behind it is saved by choosing one, so it needs somewhere to go.
  const hasCollection = $derived(nodes.some((node) => node.type === 'collection'))
  const saveable = $derived(active.path ? dirty : hasCollection)
  // Installing restarts the app, so any tab with unsaved changes is at risk.
  const anyDirty = $derived(
    tabs.list.some((tab) => tab.savedKey !== null && draftKey(tab.draft) !== tab.savedKey)
  )
  // A feed is arriving: the head is in and the body is not finished. Stopping it keeps what came.
  const streaming = $derived(
    active.inFlight && active.response?.streamed === true && active.response.ended === undefined
  )
  // Send and cancel are one button, so one value decides both what it says and what a press
  // does. A feed stops rather than cancels: what already arrived is kept.
  const sendPhase = $derived<'idle' | 'sending' | 'streaming'>(
    !active.inFlight ? 'idle' : streaming ? 'streaming' : 'sending'
  )
  // A collection is always the first path segment; requests can nest below it.
  const activeCollection = $derived(active.path ? active.path.split('/')[0] : '')

  // The exported command tracks the editor; a secret resolved only in the shell stays a
  // `{{name}}` placeholder because secret values never reach the renderer.
  const curlCommand = $derived(toCurl(active.draft, { variables: variables.resolved }))

  /**
   * How much room the open editor needs before fitting to its content makes sense. A list of
   * rows is honest at its natural height; a text surface that happens to be empty is not.
   */
  const editorFloor = $derived(
    active.editorTab === 'docs' ||
      (active.editorTab === 'body' && (active.draft.body.type === 'json' || active.draft.body.type === 'raw'))
      ? 260
      : 0
  )

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
        tab.responseCap = capByRequest.get(start.requestId) ?? DEFAULT_DISPLAY_CAP
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

  /**
   * Saves the open tab. A tab already bound to a file writes straight through; a scratch tab
   * has nowhere to write yet, so it asks which collection should hold it first.
   */
  async function save(): Promise<void> {
    if (!active.path) {
      if (hasCollection) {
        choosingSaveTarget = true
      }
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

  /**
   * Gives a scratch tab a file under `target` and writes the draft into it. The tab follows
   * the new path, so the next save goes straight through and the sidebar shows it selected.
   */
  async function saveInto(target: string, name: string): Promise<void> {
    const tab = active
    choosingSaveTarget = false
    try {
      // The core sanitises and de-duplicates the file name, so the path it returns is the
      // one to bind to — not one built from the name here.
      const path = await createRequest(target, name)
      tab.draft.name = name
      // Bound before the secrets are lifted, so an auth value is filed under the same name
      // this request's later saves will use: those are seeded from the path, not the name.
      tab.path = path
      await protectAuthSecrets(tab)
      await writeRequest(path, draftToStored(tab.draft))
      bindTab(tab, path)
      lastSaveTarget = target
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

  async function onRenameEnvironment(path: string, name: string): Promise<boolean> {
    try {
      const renamed = await renameEnvironment(path, name)
      // A run set up with this environment keeps it under its new path.
      if (run.environment === path) {
        run.environment = renamed
      }
      storeError = ''
      return true
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
      return false
    }
  }

  async function onDeleteEnvironment(path: string): Promise<void> {
    const name = variables.environments.find((entry) => entry.path === path)?.name ?? path
    const proceed = await confirmDialog(
      `Delete the environment "${name}"? Its file is removed from the collection.`,
      { confirmLabel: 'Delete', destructive: true }
    )
    if (!proceed) {
      return
    }
    try {
      await deleteEnvironment(path)
      if (run.environment === path) {
        run.environment = ''
      }
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

  function readEditorCollapsed(): boolean {
    try {
      return localStorage.getItem('ping.editor.collapsed') === 'true'
    } catch {
      return false
    }
  }

  /**
   * Folds the request editor down to its tab strip, handing the rest of the split to the
   * response. The split itself is untouched, so expanding returns to the same height.
   */
  function setEditorCollapsed(value: boolean): void {
    if (editorCollapsed === value) {
      return
    }
    editorCollapsed = value
    try {
      localStorage.setItem('ping.editor.collapsed', String(value))
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

  /** The display cap each in-flight request was sent with, keyed by request id. */
  const capByRequest = new Map<string, number>()

  /**
   * `maxBodyBytes` overrides the draft's display cap for this one send, without editing the
   * request: the truncation banner uses it to fetch a body that did not fit.
   */
  async function send(override: { maxBodyBytes?: number } = {}): Promise<void> {
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
      if (override.maxBodyBytes != null) spec.maxBodyBytes = override.maxBodyBytes
      // Recorded now and applied when the response lands: the previous response stays on
      // screen meanwhile, and its banner must keep describing the cap it was read under.
      capByRequest.set(requestId, spec.maxBodyBytes && spec.maxBodyBytes > 0 ? spec.maxBodyBytes : DEFAULT_DISPLAY_CAP)
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
      tab.responseCap = capByRequest.get(requestId) ?? DEFAULT_DISPLAY_CAP
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
      capByRequest.delete(requestId)
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

  /**
   * The truncation banner's resend. Sending again repeats whatever the request does, so a
   * method that can change server state asks first.
   */
  async function resendWithCap(maxBodyBytes: number): Promise<void> {
    const method = active.draft.method
    if (!isSafeMethod(method)) {
      const proceed = await confirmDialog(
        `Fetching the whole body sends this ${method} request again, which may repeat its effect on the server. Send it again?`,
        { confirmLabel: 'Send again', destructive: true }
      )
      if (!proceed) {
        return
      }
    }
    await send({ maxBodyBytes })
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
      { id: 'logs', label: 'Show logs…', run: () => (showLogs = true) },
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

<!--
  One face of the send button. The inactive faces stay in the layout — faded, unclickable
  and slid out of the way — rather than being removed, so the button's width is the width
  of the widest label in every phase. Send leaves upwards and the in-flight faces arrive
  from below, which reads as one strip rolling past instead of two labels crossfading.
-->
{#snippet sendFace(phase: 'idle' | 'sending' | 'streaming', text: string)}
  <span
    data-face={phase}
    data-active={sendPhase === phase}
    aria-hidden={sendPhase !== phase}
    class="col-start-1 row-start-1 flex items-center justify-center gap-2 transition
           duration-200 ease-out motion-reduce:transition-none
           {sendPhase === phase
      ? 'translate-y-0 scale-100 opacity-100'
      : `pointer-events-none scale-95 opacity-0 ${phase === 'idle' ? '-translate-y-2' : 'translate-y-2'}`}"
  >
    {text}
    {#if phase === 'sending'}
      <!-- Never hidden on hover: the pointer is on this button the moment a request starts. -->
      {@render sendIcon('spinner')}
    {:else if phase === 'streaming'}
      {@render sendIcon('stop')}
    {:else}
      {@render sendIcon('arrow')}
    {/if}
  </span>
{/snippet}

{#snippet sendIcon(shape: 'arrow' | 'spinner' | 'stop')}
  <svg
    viewBox="0 0 24 24"
    class="h-4 w-4 {shape === 'arrow'
      ? 'transition-transform duration-200 group-hover:translate-x-0.5 motion-reduce:transition-none'
      : ''} {shape === 'spinner' && sendPhase === 'sending' ? 'animate-spin' : ''}"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    {#if shape === 'arrow'}
      <line x1="4" y1="12" x2="19" y2="12" />
      <polyline points="13 6 19 12 13 18" />
    {:else if shape === 'spinner'}
      <circle cx="12" cy="12" r="9" class="opacity-30" />
      <path d="M21 12a9 9 0 0 0-9-9" />
    {:else}
      <rect x="6" y="6" width="12" height="12" rx="1.5" />
    {/if}
  </svg>
{/snippet}

<div class="relative flex h-full">
  {#snippet mainContent()}
    <main class="flex min-w-0 flex-1 flex-col gap-3 p-5">
    <!--
      Edge to edge and flush with the top: the strip's rule separates it from the page, so it
      ignores the gutter, and it carries the app's own controls instead of a title row above it.
    -->
    <div class="-mx-5 -mt-5">
      <RequestTabs
        tabs={tabs.list}
        activeId={tabs.activeId}
        onActivate={activateTab}
        onClose={closeRequestTab}
        onNew={newTab}
      >
        {#snippet leading()}
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
        {/snippet}

        {#snippet trailing()}
          <UpdateBadge hasUnsaved={anyDirty} />

          <select
            value={variables.environment}
            onchange={(event) => void onEnvironmentChange(event.currentTarget.value)}
            aria-label="Environment"
            class="rounded-md border border-line bg-panel px-2 py-1 text-xs text-fg-muted
                   outline-none transition hover:text-fg focus:border-accent focus:ring-3 focus:ring-accent/15"
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
                  class="motion-rise absolute right-0 top-full z-30 mt-1 w-52 space-y-1 rounded-lg border
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
        {/snippet}
      </RequestTabs>
    </div>

    <div
      id="request-tabpanel"
      role="tabpanel"
      aria-labelledby={`request-tab-${tabs.activeId}`}
      class="flex min-h-0 flex-1 flex-col gap-3"
    >
      <form
        class="flex gap-2.5"
        onsubmit={(event) => {
          event.preventDefault()
          // The submit button is the cancel button while an exchange is in flight, so the
          // form dispatches on the phase: Enter in the URL field does what the button says.
          if (active.inFlight) {
            void cancel()
          } else {
            void send()
          }
        }}
      >
        <!--
          Verb, URL and the two things you do with a URL are one control: a single field that
          lights up as a whole on focus, with hairlines rather than gaps between its parts.
        -->
        <div
          class="relative flex min-w-0 flex-1 items-center rounded-lg border bg-panel transition
                 {urlRequired ? 'border-warning' : 'border-line focus-within:border-accent focus-within:ring-3 focus-within:ring-accent/15'}"
        >
          <div class="relative shrink-0">
            <!--
              A ghost of the chosen verb sets the width: a select is as wide as its longest
              option, so GET would otherwise reserve the room OPTIONS needs and leave a hole
              in front of the chevron.
            -->
            <span aria-hidden="true" class="invisible block py-2.5 pl-4 pr-7 text-sm font-semibold">
              {active.draft.method}
            </span>
            <select
              bind:value={active.draft.method}
              aria-label="HTTP method"
              class="absolute inset-0 w-full appearance-none bg-transparent pl-4 pr-7 text-sm
                     font-semibold outline-none {methodTone(active.draft.method)}"
            >
              {#each METHODS as verb (verb)}
                <!-- The closed select wears the verb's colour; the open list stays readable. -->
                <option value={verb} class="bg-panel font-medium text-fg">{verb}</option>
              {/each}
            </select>
            <svg
              viewBox="0 0 24 24"
              class="pointer-events-none absolute right-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2
                     text-fg-faint"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </div>

          <span class="h-5 w-px shrink-0 bg-line"></span>

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
            class="min-w-0 flex-1 bg-transparent py-2.5 pl-3.5 pr-2 font-mono text-sm outline-none"
          />
          {#if urlRequired}
            <span
              data-role="url-required"
              role="status"
              class="motion-rise pointer-events-none absolute -top-7 left-0 whitespace-nowrap rounded-md
                     border border-warning-soft bg-panel px-2 py-1 text-xs text-warning"
            >
              Enter a URL to send
            </span>
          {/if}
          <div class="flex shrink-0 items-center gap-1 pr-1.5">
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
                  class="motion-rise pointer-events-none absolute -top-8 right-0 whitespace-nowrap rounded-md
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
              disabled={!saveable}
              aria-label={active.path ? 'Save request' : 'Save request to a collection'}
              title={active.path
                ? 'Save changes'
                : hasCollection
                  ? 'Save to a collection'
                  : 'Create a collection first'}
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

        <!--
          One button does both jobs, so it is never disabled: while an exchange is in flight
          the same press cancels it. It keeps its size across that change because every
          phase is rendered into the same grid cell, which is therefore always as wide as
          the widest label — nothing resizes under a pointer that is on its way to click.
        -->
        <button
          type="submit"
          data-role="send"
          data-state={sendPhase}
          aria-label={sendPhase === 'idle'
            ? 'Send request'
            : sendPhase === 'streaming'
              ? 'Stop the stream'
              : 'Cancel the request'}
          class="group relative grid shrink-0 overflow-hidden rounded-lg border px-7 py-2.5
                 text-sm font-medium shadow-sm transition duration-200 active:scale-[0.98]
                 motion-reduce:transition-none
                 {active.inFlight
            ? 'border-danger/60 bg-danger/10 text-danger hover:bg-danger/15'
            : 'border-accent bg-accent text-white hover:brightness-110'}"
        >
          {@render sendFace('idle', 'Send')}
          {@render sendFace('sending', 'Cancel')}
          {@render sendFace('streaming', 'Stop')}
          {#if active.inFlight}
            <!--
              Indeterminate on purpose: a response with no Content-Length has no percentage
              to show. Absolutely positioned, so it never touches the button's size.
            -->
            <span
              data-role="send-progress"
              aria-hidden="true"
              class="pointer-events-none absolute inset-x-0 bottom-0 h-[3px] overflow-hidden
                     bg-danger/25"
            >
              <span class="block h-full w-2/5 animate-sweep bg-danger"></span>
            </span>
          {/if}
        </button>
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
            class="rounded-md p-1.5 text-fg-faint transition hover:bg-line/60 hover:text-fg"
          >
            <svg
              viewBox="0 0 24 24"
              class="h-3.5 w-3.5"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              aria-hidden="true"
            >
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
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
          <button
            type="button"
            data-role="core-state-logs"
            onclick={() => (showLogs = true)}
            class="ml-1 underline underline-offset-2 hover:brightness-125"
          >
            Show logs
          </button>
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
            class="rounded-md p-1.5 text-fg-faint transition hover:bg-line/60 hover:text-fg"
          >
            <svg
              viewBox="0 0 24 24"
              class="h-3.5 w-3.5"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              aria-hidden="true"
            >
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
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

      <SplitPane
        storageKey="ping.split.request"
        label="Resize request and response"
        fit
        fitMin={editorFloor}
        firstCollapsed={editorCollapsed}
      >
        {#snippet first()}
          <section
            data-role="request"
            class="flex min-h-0 flex-col overflow-hidden rounded-lg border border-line bg-panel"
          >
            <!--
              Picking a tab is asking to see it: a collapsed editor opens rather than
              changing out of sight.
            -->
            <Tabs
              tabs={requestTabs}
              bind:active={active.editorTab}
              idPrefix="request"
              onSelect={() => setEditorCollapsed(false)}
            >
              {#snippet trailing()}
                <button
                  type="button"
                  onclick={() => setEditorCollapsed(!editorCollapsed)}
                  aria-expanded={!editorCollapsed}
                  aria-controls="request-panel"
                  aria-label={editorCollapsed ? 'Expand the request editor' : 'Collapse the request editor'}
                  title={editorCollapsed ? 'Expand the request editor' : 'Collapse the request editor'}
                  class="rounded-md p-1 text-fg-faint transition hover:bg-line/60 hover:text-fg"
                >
                  <svg
                    viewBox="0 0 24 24"
                    class="h-4 w-4 transition-transform duration-200 motion-reduce:transition-none
                           {editorCollapsed ? '' : 'rotate-180'}"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    aria-hidden="true"
                  >
                    <polyline points="6 9 12 15 18 9" />
                  </svg>
                </button>
              {/snippet}
            </Tabs>

            <div
              id="request-panel"
              role="tabpanel"
              aria-labelledby={`request-tab-${active.editorTab}`}
              class="min-h-0 flex-auto {editorCollapsed ? 'hidden' : ''}"
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
            cap={active.responseCap}
            onResend={(maxBodyBytes) => void resendWithCap(maxBodyBytes)}
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
          {onRenameEnvironment}
          {onDeleteEnvironment}
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

  {#if showLogs}
    <LogsPanel onClose={() => (showLogs = false)} />
  {/if}

  {#if run.open}
    <RunPanel />
  {/if}

  {#if choosingSaveTarget}
    <SaveRequestDialog
      {nodes}
      name={active.draft.name}
      preferred={lastSaveTarget}
      onSave={(target, name) => void saveInto(target, name)}
      onCancel={() => (choosingSaveTarget = false)}
    />
  {/if}

  <ConfirmDialog />
</div>
