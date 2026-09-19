<script lang="ts">
  import type { HttpResponse } from '../lib/http'
  import { formatBytes, formatDuration, statusTone, versionLabel } from '../lib/format'
  import { parseCookies } from '../lib/response'
  import ResponseBody from './ResponseBody.svelte'
  import ResponseCookies from './ResponseCookies.svelte'
  import ResponseHeaders from './ResponseHeaders.svelte'
  import ResponseTiming from './ResponseTiming.svelte'
  import Tabs from './Tabs.svelte'

  interface Props {
    response: HttpResponse | null
    inFlight: boolean
  }

  let { response, inFlight }: Props = $props()

  let tab = $state('body')

  const cookies = $derived(response ? parseCookies(response.headers) : [])
  const tabs: { id: string; label: string; badge: string | null }[] = $derived([
    { id: 'body', label: 'Body', badge: null },
    {
      id: 'headers',
      label: 'Headers',
      badge: response && response.headers.length > 0 ? String(response.headers.length) : null
    },
    { id: 'cookies', label: 'Cookies', badge: cookies.length > 0 ? String(cookies.length) : null },
    { id: 'timing', label: 'Timing', badge: null }
  ])
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
      <span class="text-fg-muted">{versionLabel(response.httpVersion)}</span>
      <span class="text-fg-muted">{formatBytes(response.body.bytes)}</span>
      <span class="text-fg-muted">{formatDuration(response.timing.totalMs)}</span>
      {#if response.redirects.length > 0}
        <span class="text-fg-muted">
          followed {response.redirects.length}
          {response.redirects.length === 1 ? 'redirect' : 'redirects'}
        </span>
      {/if}
      {#if response.body.contentType}
        <span class="truncate text-fg-faint">{response.body.contentType}</span>
      {/if}
    </header>

    <Tabs tabs={tabs} bind:active={tab} idPrefix="response" />

    <div
      id="response-panel"
      role="tabpanel"
      aria-labelledby={`response-tab-${tab}`}
      class="min-h-0 flex-1"
    >
      {#if tab === 'body'}
        <ResponseBody {response} />
      {:else if tab === 'headers'}
        <ResponseHeaders headers={response.headers} />
      {:else if tab === 'cookies'}
        <ResponseCookies {cookies} />
      {:else}
        <ResponseTiming timing={response.timing} bytes={response.body.bytes} />
      {/if}
    </div>
  {:else}
    <div class="flex flex-1 flex-col items-center justify-center gap-1 text-sm text-fg-faint">
      {#if inFlight}
        <p>Sending request…</p>
      {:else}
        <p>No response yet.</p>
        <p class="text-xs">Send a request to see it here.</p>
      {/if}
    </div>
  {/if}
</section>
