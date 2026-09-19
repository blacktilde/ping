<script lang="ts">
  import { call, CoreError, RpcError } from './lib/core'
  import { cancelRequest, sendRequest, type HttpResponse } from './lib/http'
  import { draft } from './lib/draft.svelte'
  import { enabledCount, METHODS, toRequestSpec } from './lib/request'
  import KeyValueEditor from './components/KeyValueEditor.svelte'
  import BodyEditor from './components/BodyEditor.svelte'
  import ResponsePane from './components/ResponsePane.svelte'

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

  const queryCount = $derived(enabledCount(draft.query))
  const headerCount = $derived(enabledCount(draft.headers))

  // Proves the whole chain on startup: renderer, preload, main, core process.
  $effect(() => {
    call<CoreInfo>('core.info')
      .then((result) => (info = result))
      .catch((cause: Error) => (bootError = cause.message))
  })

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
      response = await sendRequest(toRequestSpec(draft, requestId))
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

<main class="flex h-full flex-col gap-3 p-5">
  <header class="flex items-baseline justify-between border-b border-line pb-3">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Ping</h1>
      <p class="text-sm text-neutral-500">Phase 5 — response viewer</p>
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
