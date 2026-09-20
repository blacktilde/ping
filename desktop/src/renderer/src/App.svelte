<script lang="ts">
  import { call, CoreError, RpcError } from './lib/core'
  import { authToSpec, cancelRequest, sendRequest, type RequestDraft } from './lib/http'
  import { copyText } from './lib/clipboard'
  import { toCurl } from './lib/curl'
  import { checkForUpdates, loadUpdateState, updates, watchUpdates } from './lib/updates.svelte'
  import { clearHistory, history, loadHistory, recordHistory } from './lib/history.svelte'
  import { enabledCount, METHODS, toRequestSpec } from './lib/request'
  import {
    activeTab,
    activateTab,
    closeTab,
    closeTabsUnder,
    ensureTab,
    newTab,
    openTab,
    tabs
  } from './lib/tabs.svelte'
  import {
    chooseWorkspace,
    createRequest,
    currentWorkspace,
    deleteEntry,
    draftKey,
    draftToStored,
    onStoreChanged,
    openInFileManager,
    readRequest,
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
    variables
  } from './lib/vars.svelte'
  import { loadSecretRows, persistSecretRows } from './lib/secrets.svelte'
  import { setSecret } from './lib/secrets'
  import { cycleTheme, theme } from './lib/theme.svelte'
  import KeyValueEditor from './components/KeyValueEditor.svelte'
  import BodyEditor from './components/BodyEditor.svelte'
  import AuthEditor from './components/AuthEditor.svelte'
  import ResponsePane from './components/ResponsePane.svelte'
  import Sidebar from './components/Sidebar.svelte'
  import VariablesPanel from './components/VariablesPanel.svelte'
  import RequestTabs from './components/RequestTabs.svelte'
  import Tabs from './components/Tabs.svelte'
  import SplitPane from './components/SplitPane.svelte'
  import CommandPalette from './components/CommandPalette.svelte'
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

  let nodes = $state<StoreNode[]>([])
  let storeError = $state('')
  let showVariables = $state(false)
  let paletteOpen = $state(false)
  let workspaceRoot = $state<string | null>(null)
  let sidebarCollapsed = $state(readSidebarCollapsed())
  let sidebarPanel = $state<'collections' | 'history'>('collections')
  let curlStatus = $state('')

  // The tab the editor is showing. Every per-request value lives on it, so switching tabs
  // swaps the whole editor and response state at once.
  const active = $derived(activeTab())

  const queryCount = $derived(enabledCount(active.draft.query))
  const headerCount = $derived(enabledCount(active.draft.headers))
  const dirty = $derived(
    active.savedKey !== null && draftKey(active.draft) !== active.savedKey
  )
  // Installing restarts the app, so any tab with unsaved changes is at risk.
  const anyDirty = $derived(
    tabs.list.some((tab) => tab.savedKey !== null && draftKey(tab.draft) !== tab.savedKey)
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
    { id: 'auth', label: 'Auth', badge: active.draft.auth.type === 'none' ? null : 'on' }
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
    }
  })

  // The core reports an interactive OAuth2 flow's outcome; main has already stored tokens.
  $effect(() => {
    return window.ping.onNotification((notification) => {
      if (notification.method !== 'auth.completed') {
        return
      }
      const params = notification.params as { error?: string } | null
      active.authStatus = params?.error
        ? `Authorization failed: ${params.error}`
        : 'Authorized'
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
      workspaceRoot = workspace.root
      // Tabs point at paths in the old workspace, so they cannot survive the switch.
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
  function closeRequestTab(id: string): void {
    const tab = tabs.list.find((candidate) => candidate.id === id)
    if (!tab) {
      return
    }
    const unsaved = tab.savedKey !== null && draftKey(tab.draft) !== tab.savedKey
    if (unsaved && !confirm('Discard unsaved changes?')) {
      return
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

  async function onSaveVariables(): Promise<void> {
    try {
      await persistVariables()
      await persistSecretRows()
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function onAddEnvironment(): Promise<void> {
    const name = prompt('Environment name')
    if (!name || !variables.collection) {
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
    if (tab.inFlight || target.length === 0) {
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
      const spec = toRequestSpec(tab.draft, requestId)
      if (Object.keys(variables.resolved).length > 0) {
        // A spread unwraps the reactive proxy, which cannot cross the context bridge.
        spec.variables = { ...variables.resolved }
      }
      tab.response = await sendRequest(spec)
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
      { id: 'send', label: 'Send request', hint: '⌘↵', run: () => void send() },
      { id: 'save', label: 'Save request', hint: '⌘S', run: () => void save() },
      { id: 'curl', label: 'Copy as cURL', run: () => void copyAsCurl() },
      { id: 'new-tab', label: 'New request tab', hint: '⌘T', run: newTab },
      { id: 'close-tab', label: 'Close request tab', hint: '⌘W', run: () => closeRequestTab(active.id) },
      { id: 'open', label: 'Open folder…', run: () => void openFolder() },
      {
        id: 'sidebar',
        label: sidebarCollapsed ? 'Show collections sidebar' : 'Hide collections sidebar',
        hint: '⌘B',
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
        label: theme.resolved === 'dark' ? 'Theme: light' : 'Theme: dark',
        run: cycleTheme
      },
      { id: 'tab-params', label: 'Go to Params', run: () => (active.editorTab = 'params') },
      { id: 'tab-headers', label: 'Go to Headers', run: () => (active.editorTab = 'headers') },
      { id: 'tab-body', label: 'Go to Body', run: () => (active.editorTab = 'body') },
      { id: 'tab-auth', label: 'Go to Auth', run: () => (active.editorTab = 'auth') },
      { id: 'env-none', label: 'Environment: none', run: () => void onEnvironmentChange('') }
    ]

    if (updates.state.enabled) {
      commands.push({
        id: 'check-updates',
        label: 'Check for updates',
        run: () => void checkForUpdates()
      })
    }
    if (activeCollection) {
      commands.push({ id: 'new', label: 'New request', run: () => void createIn(activeCollection) })
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

<div class="flex h-full">
  {#snippet mainContent()}
    <main class="flex min-w-0 flex-1 flex-col gap-3 p-5">
      <header class="flex items-center justify-between gap-4 border-b border-line pb-3">
        <div class="flex items-center gap-2.5">
          <button
            type="button"
            onclick={toggleSidebar}
            aria-label={sidebarCollapsed ? 'Show collections sidebar' : 'Hide collections sidebar'}
            title={sidebarCollapsed
              ? 'Show collections sidebar (⌘B)'
              : 'Hide collections sidebar (⌘B)'}
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
            <dl class="ml-2 flex gap-5 text-xs text-fg-muted">
              <div><dt class="inline text-fg-faint">core</dt> <dd class="inline">{info.coreVersion}</dd></div>
              <div><dt class="inline text-fg-faint">java</dt> <dd class="inline">{info.javaVersion}</dd></div>
              <div>
                <dt class="inline text-fg-faint">mode</dt>
                <dd class="inline">{info.nativeImage ? 'native-image' : 'jvm'}</dd>
              </div>
            </dl>
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
            bind:value={active.draft.url}
            aria-label="Request URL"
            spellcheck="false"
            autocomplete="off"
            placeholder="https://api.example.com/resource"
            class="w-full rounded-lg border border-line bg-panel py-2.5 pl-4 pr-16 font-mono
                   text-sm outline-none transition focus:border-accent"
          />
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
          {active.inFlight ? 'Sending…' : 'Send'}
        </button>

        {#if active.inFlight}
          <button
            type="button"
            onclick={cancel}
            class="rounded-lg border border-line px-4 py-2.5 text-sm text-fg
                   transition hover:border-fg-muted"
          >
            Cancel
          </button>
        {/if}
      </form>

      {#if storeError}
        <p
          data-role="store-error"
          role="alert"
          class="rounded-lg border border-amber-900/60 bg-amber-950/30 px-4 py-3 text-sm text-amber-300"
        >
          {storeError}
        </p>
      {/if}

      {#if active.error || bootError}
        <p
          data-role="error"
          role="alert"
          class="rounded-lg border border-red-900/60 bg-red-950/40 px-4 py-3 text-sm text-red-300"
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
                <BodyEditor body={active.draft.body} />
              {:else}
                <AuthEditor
                  auth={active.draft.auth}
                  status={active.authStatus}
                  onAuthorize={authorize}
                />
              {/if}
            </div>
          </section>
        {/snippet}

        {#snippet second()}
          <ResponsePane response={active.response} inFlight={active.inFlight} />
        {/snippet}
      </SplitPane>
    </div>
    </main>
  {/snippet}

  {#if sidebarCollapsed}
    {@render mainContent()}
  {:else}
    <SplitPane
      direction="horizontal"
      unit="pixels"
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
          onDelete={deleteNode}
          onOpenLocation={openLocation}
          onNewCollection={newCollection}
          onOpenFolder={openFolder}
          onSelectHistory={selectHistory}
          onClearHistory={clearHistoryEntries}
        />
      {/snippet}

      {#snippet second()}
        {@render mainContent()}
      {/snippet}
    </SplitPane>
  {/if}

  {#if showVariables}
    <VariablesPanel
      onClose={() => (showVariables = false)}
      onSave={onSaveVariables}
      onAddEnvironment={onAddEnvironment}
    />
  {/if}

  <CommandPalette bind:open={paletteOpen} commands={paletteCommands} />
</div>
