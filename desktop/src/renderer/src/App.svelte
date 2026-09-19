<script lang="ts">
  import { call, CoreError, RpcError } from './lib/core'
  import { cancelRequest, sendRequest, type HttpResponse } from './lib/http'
  import { draft, loadDraft } from './lib/draft.svelte'
  import { enabledCount, METHODS, toRequestSpec } from './lib/request'
  import {
    chooseWorkspace,
    createRequest,
    currentWorkspace,
    draftKey,
    draftToStored,
    onStoreChanged,
    readRequest,
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
  import KeyValueEditor from './components/KeyValueEditor.svelte'
  import BodyEditor from './components/BodyEditor.svelte'
  import ResponsePane from './components/ResponsePane.svelte'
  import Sidebar from './components/Sidebar.svelte'
  import VariablesPanel from './components/VariablesPanel.svelte'

  interface CoreInfo {
    coreVersion: string
    javaVersion: string
    vendor: string
    nativeImage: boolean
  }

  type Tab = 'params' | 'headers' | 'body'

  const tabs: { id: Tab; label: string }[] = [
    { id: 'params', label: 'Params' },
    { id: 'headers', label: 'Headers' },
    { id: 'body', label: 'Body' }
  ]

  let info = $state<CoreInfo | null>(null)
  let bootError = $state('')
  let tab = $state<Tab>('params')
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

  const queryCount = $derived(enabledCount(draft.query))
  const headerCount = $derived(enabledCount(draft.headers))
  const dirty = $derived(savedKey !== null && draftKey(draft) !== savedKey)
  // A collection is always the first path segment; requests can nest below it.
  const activeCollection = $derived(activePath ? activePath.split('/')[0] : '')

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

  async function refresh(options: { autoOpen?: boolean } = {}): Promise<void> {
    try {
      const workspace = await currentWorkspace()
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
      await writeRequest(activePath, draftToStored(draft))
      savedKey = draftKey(draft)
      storeError = ''
    } catch (cause) {
      storeError = cause instanceof Error ? cause.message : String(cause)
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
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      void save()
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

  function tabBadge(id: Tab): string | null {
    if (id === 'params') return queryCount > 0 ? String(queryCount) : null
    if (id === 'headers') return headerCount > 0 ? String(headerCount) : null
    return draft.body.type === 'none' ? null : '•'
  }
</script>

<svelte:window onkeydown={onKeydown} />

<div class="flex h-full">
  <Sidebar
    {nodes}
    {activePath}
    onSelect={selectNode}
    onCreate={createIn}
    onOpenFolder={openFolder}
  />

  <main class="flex min-w-0 flex-1 flex-col gap-3 p-5">
    <header class="flex items-baseline justify-between border-b border-line pb-3">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Ping</h1>
        <p class="text-sm text-neutral-500">Phase 6 — collections on disk</p>
      </div>

      {#if info}
        <dl class="flex gap-5 text-xs text-neutral-400">
          <div><dt class="inline text-neutral-600">core</dt> <dd class="inline">{info.coreVersion}</dd></div>
          <div><dt class="inline text-neutral-600">java</dt> <dd class="inline">{info.javaVersion}</dd></div>
          <div>
            <dt class="inline text-neutral-600">mode</dt>
            <dd class="inline">{info.nativeImage ? 'native-image' : 'jvm'}</dd>
          </div>
        </dl>
      {:else if !bootError}
        <span class="text-xs text-neutral-600">connecting to core…</span>
      {/if}
    </header>

    <div class="flex items-center gap-3">
      <input
        bind:value={draft.name}
        aria-label="Request name"
        class="min-w-0 flex-1 rounded-lg border border-line bg-panel px-3 py-2 text-sm
               text-neutral-200 outline-none transition focus:border-accent"
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
        class="rounded-lg border border-line bg-panel px-3 py-2 text-sm text-neutral-300
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
        class="rounded-lg border border-line px-4 py-2 text-sm text-neutral-300 transition
               hover:border-accent"
      >
        Variables
      </button>
      <button
        data-role="save"
        type="button"
        onclick={save}
        disabled={!activePath || !dirty}
        class="rounded-lg border border-line px-4 py-2 text-sm text-neutral-300 transition
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
          class="rounded-lg border border-line px-4 py-2.5 text-sm text-neutral-300
                 transition hover:border-neutral-500"
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
        class="rounded-lg border border-line bg-panel px-4 py-3 text-sm text-neutral-400"
      >
        Request cancelled.
      </p>
    {/if}

    <div class="grid min-h-0 flex-1 grid-rows-2 gap-4">
      <section
        data-role="request"
        class="flex min-h-0 flex-col overflow-hidden rounded-lg border border-line bg-panel"
      >
        <div role="tablist" class="flex items-center gap-1 border-b border-line px-2">
          {#each tabs as entry (entry.id)}
            <button
              type="button"
              role="tab"
              aria-selected={tab === entry.id}
              onclick={() => (tab = entry.id)}
              class="flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm transition
                     {tab === entry.id
                ? 'border-accent text-neutral-100'
                : 'border-transparent text-neutral-500 hover:text-neutral-300'}"
            >
              {entry.label}
              {#if tabBadge(entry.id)}
                <span class="rounded-full bg-line px-1.5 text-[10px] text-neutral-400">
                  {tabBadge(entry.id)}
                </span>
              {/if}
            </button>
          {/each}
        </div>

        <div class="min-h-0 flex-1">
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
          {:else}
            <BodyEditor body={draft.body} />
          {/if}
        </div>
      </section>

      <ResponsePane {response} {inFlight} />
    </div>
  </main>

  {#if showVariables}
    <VariablesPanel
      onClose={() => (showVariables = false)}
      onSave={onSaveVariables}
      onAddEnvironment={onAddEnvironment}
    />
  {/if}
</div>
