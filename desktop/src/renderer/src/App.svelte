<script lang="ts">
  import {
    cancelRequest,
    sendRequest,
    type HttpMethod,
    type HttpResponse
  } from './lib/http'
  import { call, CoreError, RpcError } from './lib/core'

  interface CoreInfo {
    coreVersion: string
    javaVersion: string
    vendor: string
    nativeImage: boolean
  }

  const methods: HttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']

  let info = $state<CoreInfo | null>(null)
  let bootError = $state('')
  let method = $state<HttpMethod>('GET')
  let url = $state('https://jsonplaceholder.typicode.com/todos/1')
  let response = $state<HttpResponse | null>(null)
  let error = $state('')
  let cancelled = $state(false)
  let inFlight = $state(false)
  let activeRequestId = $state('')

  // Proves the whole chain on startup: renderer, preload, main, core process.
  $effect(() => {
    call<CoreInfo>('core.info')
      .then((result) => (info = result))
      .catch((cause: Error) => (bootError = cause.message))
  })

  async function send(): Promise<void> {
    const target = url.trim()
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
      response = await sendRequest(target, method, requestId)
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

  function statusTone(status: number): string {
    if (status >= 200 && status < 300) return 'text-emerald-400'
    if (status >= 300 && status < 400) return 'text-amber-400'
    if (status >= 400) return 'text-red-400'
    return 'text-neutral-300'
  }

  function versionLabel(version: string): string {
    return version === 'HTTP_2' ? 'HTTP/2' : 'HTTP/1.1'
  }

  function formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  }
</script>

<main class="flex h-full flex-col gap-4 p-6">
  <header class="flex items-baseline justify-between border-b border-line pb-3">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Ping</h1>
      <p class="text-sm text-neutral-500">Phase 3 — live request</p>
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
      bind:value={method}
      aria-label="HTTP method"
      class="rounded-lg border border-line bg-panel px-3 py-2.5 text-sm font-medium outline-none
             transition focus:border-accent"
    >
      {#each methods as verb (verb)}
        <option value={verb}>{verb}</option>
      {/each}
    </select>

    <input
      bind:value={url}
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

  <section
    aria-busy={inFlight}
    class="flex flex-1 flex-col overflow-hidden rounded-lg border border-line bg-panel"
  >
    {#if response}
      <header class="flex items-center gap-4 border-b border-line px-4 py-2.5 text-xs">
        <span class="font-mono text-sm font-semibold {statusTone(response.status)}">
          {response.status}
        </span>
        <span class="text-neutral-500">{versionLabel(response.httpVersion)}</span>
        <span class="text-neutral-500">{formatBytes(response.body.bytes)}</span>
        {#if response.redirects.length > 0}
          <span class="text-neutral-500">
            followed {response.redirects.length}
            {response.redirects.length === 1 ? 'redirect' : 'redirects'}
          </span>
        {/if}
        {#if response.body.contentType}
          <span class="truncate text-neutral-600">{response.body.contentType}</span>
        {/if}
      </header>

      {#if response.body.truncated}
        <p class="border-b border-amber-900/50 bg-amber-950/30 px-4 py-2 text-xs text-amber-300">
          Response is larger than the display cap; only the beginning is shown.
        </p>
      {/if}

      {#if response.body.textual}
        <pre
          class="flex-1 overflow-auto p-4 font-mono text-sm leading-relaxed text-neutral-300">{response.body.content ||
            '(empty body)'}</pre>
      {:else}
        <div class="flex flex-1 items-center justify-center text-sm text-neutral-600">
          Binary response — {formatBytes(response.body.bytes)} not displayed
        </div>
      {/if}
    {:else}
      <div class="flex flex-1 items-center justify-center text-sm text-neutral-600">
        Send a request to see the response.
      </div>
    {/if}
  </section>
</main>
