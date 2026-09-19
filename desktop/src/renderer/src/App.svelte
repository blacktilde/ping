<script lang="ts">
  import { call, CoreError, RpcError } from './lib/core'
  import { authToSpec, cancelRequest, sendRequest, type HttpResponse } from './lib/http'
  import { draft, loadDraft } from './lib/draft.svelte'
  import { enabledCount, METHODS, toRequestSpec } from './lib/request'
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
    type StoreNode
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
  import Tabs from './components/Tabs.svelte'
  import SplitPane from './components/SplitPane.svelte'
  import CommandPalette from './components/CommandPalette.svelte'

  interface CoreInfo {
    coreVersion: string
    javaVersion: string
    vendor: string
    nativeImage: boolean
  }

  let info = $state<CoreInfo | null>(null)
  let bootError = $state('')
  let tab = $state('params')
  let response = $state<HttpResponse | null>(null)
  let error = $state('')
  let cancelled = $state(false)
  let inFlight = $state(false)
  let activeRequestId = $state('')

  let nodes = $state<StoreNode[]>([])
  let activePath = $state<string | null>(null)
  let savedKey = $state<string | null>(null)
  let storeError = $state('')
  let showVariables = $state(false)
  let authStatus = $state('')
  let paletteOpen = $state(false)
  let workspaceRoot = $state<string | null>(null)

  const queryCount = $derived(enabledCount(draft.query))
  const headerCount = $derived(enabledCount(draft.headers))
  const dirty = $derived(savedKey !== null && draftKey(draft) !== savedKey)
  // A collection is always the first path segment; requests can nest below it.
  const activeCollection = $derived(activePath ? activePath.split('/')[0] : '')

  const requestTabs = $derived([
    { id: 'params', label: 'Params', badge: queryCount > 0 ? String(queryCount) : null },
    { id: 'headers', label: 'Headers', badge: headerCount > 0 ? String(headerCount) : null },
    { id: 'body', label: 'Body', badge: draft.body.type === 'none' ? null : '•' },
    { id: 'auth', label: 'Auth', badge: draft.auth.type === 'none' ? null : 'on' }
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

  // Variables follow the collection of the open request, not the workspace.
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
      authStatus = params?.error ? `Authorization failed: ${params.error}` : 'Authorized'
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
      if (options.autoOpen && activePath === null) {
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
      activePath = null
      savedKey = null
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
    if (dirty && !confirm('Discard unsaved changes?')) {
      return
    }
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
      const removedActive =
        activePath === node.path || (activePath?.startsWith(`${node.path}/`) ?? false)
      if (removedActive) {
        activePath = null
        savedKey = null
      }
      nodes = await scanStore()
      if (removedActive) {
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

  async function openRequest(node: StoreNode): Promise<void> {
    try {
      loadDraft(storedToDraft(await readRequest(node.path)))
      activePath = node.path
      savedKey = draftKey(draft)
      response = null
      error = ''
      cancelled = false
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  function selectNode(node: StoreNode): void {
    if (node.path === activePath) {
      return
    }
    if (dirty && !confirm('Discard unsaved changes?')) {
      return
    }
    void openRequest(node)
  }

  async function createIn(collectionPath: string): Promise<void> {
    if (dirty && !confirm('Discard unsaved changes?')) {
      return
    }
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
    if (!activePath) {
      return
    }
    try {
      await protectAuthSecrets()
      await writeRequest(activePath, draftToStored(draft))
      savedKey = draftKey(draft)
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
    }
  }

  const SECRET_FIELDS = ['password', 'token', 'value', 'clientSecret'] as const

  /**
   * Moves literal credentials out of the file. A secret-bearing auth field the user typed
   * into is stored in the shell and replaced with a {{name}} reference, so a collection
   * that is committed and shared never carries the value.
   */
  async function protectAuthSecrets(): Promise<void> {
    for (const field of SECRET_FIELDS) {
      const value = draft.auth[field]
      if (!value || value.includes('{{')) {
        continue
      }
      const name = secretNameFor(field)
      await setSecret(name, value)
      draft.auth[field] = `{{${name}}}`
    }
  }

  function secretNameFor(field: string): string {
    const seed = activePath ?? draft.name ?? 'request'
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
      authStatus = 'Waiting for the browser…'
      await call('auth.authorize', {
        auth: authToSpec(draft.auth),
        variables: { ...variables.resolved }
      })
    } catch (cause) {
      authStatus = cause instanceof Error ? cause.message : String(cause)
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
    } else if (event.key === 'Enter') {
      event.preventDefault()
      void send()
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
    const target = draft.url.trim()
    if (inFlight || target.length === 0) {
      return
    }

    const requestId = crypto.randomUUID()
    activeRequestId = requestId
    inFlight = true
    error = ''
    cancelled = false
    // The previous response stays on screen while the next is in flight, as Postman does.
    // The pane is aria-busy so the staleness is announced rather than hidden.

    try {
      const spec = toRequestSpec(draft, requestId)
      if (Object.keys(variables.resolved).length > 0) {
        // A spread unwraps the reactive proxy, which cannot cross the context bridge.
        spec.variables = { ...variables.resolved }
      }
      response = await sendRequest(spec)
    } catch (cause) {
      response = null
      if (cause instanceof CoreError && cause.code === RpcError.requestCancelled) {
        cancelled = true
      } else {
        error = cause instanceof Error ? cause.message : String(cause)
      }
    } finally {
      inFlight = false
      activeRequestId = ''
    }
  }

  async function cancel(): Promise<void> {
    if (!activeRequestId) {
      return
    }
    try {
      await cancelRequest(activeRequestId)
    } catch (cause) {
      error = cause instanceof Error ? cause.message : String(cause)
    }
  }

  const paletteCommands = $derived.by(() => {
    const commands: { id: string; label: string; hint?: string; run: () => void }[] = [
      { id: 'send', label: 'Send request', hint: '⌘↵', run: () => void send() },
      { id: 'save', label: 'Save request', hint: '⌘S', run: () => void save() },
      { id: 'open', label: 'Open folder…', run: () => void openFolder() },
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
      { id: 'tab-params', label: 'Go to Params', run: () => (tab = 'params') },
      { id: 'tab-headers', label: 'Go to Headers', run: () => (tab = 'headers') },
      { id: 'tab-body', label: 'Go to Body', run: () => (tab = 'body') },
      { id: 'tab-auth', label: 'Go to Auth', run: () => (tab = 'auth') },
      { id: 'env-none', label: 'Environment: none', run: () => void onEnvironmentChange('') }
    ]

    if (activeCollection) {
      commands.push({ id: 'new', label: 'New request', run: () => void createIn(activeCollection) })
    }
    if (draft.auth.type === 'oauth2-authorization-code') {
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
  <SplitPane
    direction="horizontal"
    unit="pixels"
    storageKey="ping.split.sidebar"
    label="Resize sidebar"
  >
    {#snippet first()}
      <Sidebar
        {nodes}
        {activePath}
        {workspaceRoot}
        onSelect={selectNode}
        onCreate={createIn}
        onDelete={deleteNode}
        onOpenLocation={openLocation}
        onNewCollection={newCollection}
        onOpenFolder={openFolder}
      />
    {/snippet}

    {#snippet second()}
      <main class="flex min-w-0 flex-1 flex-col gap-3 p-5">
        <header class="flex items-baseline justify-between border-b border-line pb-3">
          <div>
            <h1 class="text-xl font-semibold tracking-tight">Ping</h1>
            <p class="text-sm text-fg-muted">A desktop REST client</p>
          </div>

          {#if info}
            <dl class="flex gap-5 text-xs text-fg-muted">
              <div><dt class="inline text-fg-faint">core</dt> <dd class="inline">{info.coreVersion}</dd></div>
              <div><dt class="inline text-fg-faint">java</dt> <dd class="inline">{info.javaVersion}</dd></div>
              <div>
                <dt class="inline text-fg-faint">mode</dt>
                <dd class="inline">{info.nativeImage ? 'native-image' : 'jvm'}</dd>
              </div>
            </dl>
          {:else if !bootError}
            <span class="text-xs text-fg-faint">connecting to core…</span>
          {/if}
        </header>

        <div class="flex items-center gap-3">
          <input
            bind:value={draft.name}
            aria-label="Request name"
            class="min-w-0 flex-1 rounded-lg border border-line bg-panel px-3 py-2 text-sm
                   text-fg outline-none transition focus:border-accent"
          />
          {#if dirty}
            <span
              data-role="dirty"
              title="Unsaved changes"
              class="h-2.5 w-2.5 shrink-0 rounded-full bg-accent"
            ></span>
          {/if}
          <select
            value={variables.environment}
            onchange={(event) => void onEnvironmentChange(event.currentTarget.value)}
            aria-label="Environment"
            class="rounded-lg border border-line bg-panel px-3 py-2 text-sm text-fg
                   outline-none transition focus:border-accent"
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
            class="rounded-lg border border-line px-4 py-2 text-sm text-fg transition
                   hover:border-accent"
          >
            Variables
          </button>
          <button
            data-role="save"
            type="button"
            onclick={save}
            disabled={!activePath || !dirty}
            title={activePath
              ? 'Save changes'
              : 'Open a request from a collection, or create a collection first'}
            class="rounded-lg border border-line px-4 py-2 text-sm text-fg-muted transition
                   hover:border-accent disabled:opacity-40"
          >
            Save
          </button>
        </div>

        <form
          class="flex gap-2"
          onsubmit={(event) => {
            event.preventDefault()
            void send()
          }}
        >
          <select
            bind:value={draft.method}
            aria-label="HTTP method"
            class="rounded-lg border border-line bg-panel px-3 py-2.5 text-sm font-medium outline-none
                   transition focus:border-accent"
          >
            {#each METHODS as verb (verb)}
              <option value={verb}>{verb}</option>
            {/each}
          </select>

          <input
            bind:value={draft.url}
            aria-label="Request URL"
            spellcheck="false"
            autocomplete="off"
            placeholder="https://api.example.com/resource"
            class="flex-1 rounded-lg border border-line bg-panel px-4 py-2.5 font-mono text-sm
                   outline-none transition focus:border-accent"
          />

          <button
            type="submit"
            disabled={inFlight}
            class="rounded-lg bg-accent px-6 py-2.5 text-sm font-medium text-white
                   transition hover:brightness-110 disabled:opacity-40"
          >
            {inFlight ? 'Sending…' : 'Send'}
          </button>

          {#if inFlight}
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

        {#if error || bootError}
          <p
            data-role="error"
            role="alert"
            class="rounded-lg border border-red-900/60 bg-red-950/40 px-4 py-3 text-sm text-red-300"
          >
            {error || bootError}
          </p>
        {:else if cancelled}
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
              <Tabs tabs={requestTabs} bind:active={tab} idPrefix="request" />

              <div
                id="request-panel"
                role="tabpanel"
                aria-labelledby={`request-tab-${tab}`}
                class="min-h-0 flex-1"
              >
                {#if tab === 'params'}
                  <KeyValueEditor
                    items={draft.query}
                    nameLabel="Query parameter"
                    valueLabel="Query value"
                    addLabel="Add parameter"
                    emptyText="No query parameters yet."
                  />
                {:else if tab === 'headers'}
                  <KeyValueEditor
                    items={draft.headers}
                    nameLabel="Header name"
                    valueLabel="Header value"
                    addLabel="Add header"
                    emptyText="No headers yet."
                  />
                {:else if tab === 'body'}
                  <BodyEditor body={draft.body} />
                {:else}
                  <AuthEditor auth={draft.auth} status={authStatus} onAuthorize={authorize} />
                {/if}
              </div>
            </section>
          {/snippet}

          {#snippet second()}
            <ResponsePane {response} {inFlight} />
          {/snippet}
        </SplitPane>
      </main>
    {/snippet}
  </SplitPane>

  {#if showVariables}
    <VariablesPanel
      onClose={() => (showVariables = false)}
      onSave={onSaveVariables}
      onAddEnvironment={onAddEnvironment}
    />
  {/if}

  <CommandPalette bind:open={paletteOpen} commands={paletteCommands} />
</div>
