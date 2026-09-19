<script lang="ts">
  interface CoreInfo {
    coreVersion: string
    javaVersion: string
    vendor: string
    nativeImage: boolean
  }

  let info = $state<CoreInfo | null>(null)
  let message = $state('hello from the renderer')
  let response = $state<string>('')
  let error = $state<string>('')
  let inFlight = $state(false)

  // Proves the whole chain on startup: renderer, preload, main, core process.
  $effect(() => {
    window.ping
      .request<CoreInfo>('core.info')
      .then((result) => (info = result))
      .catch((cause: Error) => (error = cause.message))
  })

  async function send(): Promise<void> {
    inFlight = true
    error = ''
    const startedAt = performance.now()
    try {
      const result = await window.ping.request('core.ping', { message })
      const elapsed = Math.round(performance.now() - startedAt)
      response = `${JSON.stringify(result, null, 2)}\n\n// round trip: ${elapsed}ms`
    } catch (cause) {
      error = cause instanceof Error ? cause.message : String(cause)
      response = ''
    } finally {
      inFlight = false
    }
  }
</script>

<main class="flex h-full flex-col gap-6 p-8">
  <header class="flex items-baseline justify-between border-b border-line pb-4">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Ping</h1>
      <p class="text-sm text-neutral-500">Phase 0 — renderer to core round trip</p>
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
    {:else if !error}
      <span class="text-xs text-neutral-600">connecting to core…</span>
    {/if}
  </header>

  <div class="flex gap-3">
    <input
      bind:value={message}
      onkeydown={(event) => event.key === 'Enter' && send()}
      placeholder="Message to echo"
      class="flex-1 rounded-lg border border-line bg-panel
             px-4 py-2.5 text-sm outline-none transition focus:border-accent"
    />
    <button
      onclick={send}
      disabled={inFlight}
      class="rounded-lg bg-accent px-6 py-2.5 text-sm font-medium text-white
             transition hover:brightness-110 disabled:opacity-40"
    >
      {inFlight ? 'Sending…' : 'Send'}
    </button>
  </div>

  {#if error}
    <p class="rounded-lg border border-red-900/60 bg-red-950/40 px-4 py-3 text-sm text-red-300">
      {error}
    </p>
  {/if}

  <pre
    class="flex-1 overflow-auto rounded-lg border border-line
           bg-panel p-4 text-sm text-neutral-300">{response ||
      'No response yet.'}</pre>
</main>
