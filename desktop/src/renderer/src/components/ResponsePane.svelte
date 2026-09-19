<script lang="ts">
  import type { HttpResponse } from '../lib/http'

  interface Props {
    response: HttpResponse | null
    inFlight: boolean
  }

  let { response, inFlight }: Props = $props()

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

<section
  data-role="response"
  aria-busy={inFlight}
  class="flex min-h-0 flex-col overflow-hidden rounded-lg border border-line bg-panel"
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
      {inFlight ? 'Sending request…' : 'Send a request to see the response.'}
    </div>
  {/if}
</section>
