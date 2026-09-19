<script lang="ts">
  import type { HttpResponse } from '../lib/http'
  import { formatBytes, formatDuration, statusTone, versionLabel } from '../lib/format'
  import { parseCookies } from '../lib/response'
  import ResponseBody from './ResponseBody.svelte'
  import ResponseCookies from './ResponseCookies.svelte'
  import ResponseHeaders from './ResponseHeaders.svelte'
  import ResponseTiming from './ResponseTiming.svelte'

  interface Props {
    response: HttpResponse | null
    inFlight: boolean
  }

  type Tab = 'body' | 'headers' | 'cookies' | 'timing'

  let { response, inFlight }: Props = $props()

  let tab = $state<Tab>('body')

  const cookies = $derived(response ? parseCookies(response.headers) : [])
  const tabs: { id: Tab; label: string; badge: string | null }[] = $derived([
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
      <span class="text-neutral-500">{versionLabel(response.httpVersion)}</span>
      <span class="text-neutral-500">{formatBytes(response.body.bytes)}</span>
      <span class="text-neutral-500">{formatDuration(response.timing.totalMs)}</span>
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
          {#if entry.badge}
            <span class="rounded-full bg-line px-1.5 text-[10px] text-neutral-400">
              {entry.badge}
            </span>
          {/if}
        </button>
      {/each}
    </div>

    <div class="min-h-0 flex-1">
      {#if tab === 'body'}
        {#key response}
          <ResponseBody {response} />
        {/key}
      {:else if tab === 'headers'}
        <ResponseHeaders headers={response.headers} />
      {:else if tab === 'cookies'}
        <ResponseCookies {cookies} />
      {:else}
        <ResponseTiming timing={response.timing} bytes={response.body.bytes} />
      {/if}
    </div>
  {:else}
    <div class="flex flex-1 items-center justify-center text-sm text-neutral-600">
      {inFlight ? 'Sending request…' : 'Send a request to see the response.'}
    </div>
  {/if}
</section>
