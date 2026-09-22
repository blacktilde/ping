<script lang="ts">
  import type { HttpResponse } from '../lib/http'
  import { probeOrigin, type ProbeResult } from '../lib/probe'
  import type { SseEvent } from '../lib/sse'
  import { formatBytes, formatDuration, reasonPhrase, statusTone, versionLabel } from '../lib/format'
  import { parseCookies } from '../lib/response'
  import ResponseAssertions from './ResponseAssertions.svelte'
  import ResponseCaptures from './ResponseCaptures.svelte'
  import ResponseBody from './ResponseBody.svelte'
  import ResponseCookies from './ResponseCookies.svelte'
  import ResponseHeaders from './ResponseHeaders.svelte'
  import ResponseTiming from './ResponseTiming.svelte'
  import Tabs from './Tabs.svelte'

  interface Props {
    response: HttpResponse | null
    inFlight: boolean
    suggestedName?: string
    /** Whether the request verifies TLS; the probe follows it. */
    verifyTls?: boolean
    /** Events parsed as a server-sent stream arrived. */
    events?: SseEvent[]
  }

  let { response, inFlight, suggestedName = 'response', verifyTls = true, events = [] }: Props = $props()

  // The probe belongs to the response it was run for, and survives switching response tabs.
  let probe = $state<ProbeResult | null>(null)
  let probeError = $state('')
  let probing = $state(false)

  $effect(() => {
    void response
    probe = null
    probeError = ''
  })

  async function runProbe(): Promise<void> {
    const origin = response?.origin
    if (!origin) return
    probing = true
    probeError = ''
    try {
      probe = await probeOrigin(origin, verifyTls)
    } catch (cause) {
      probe = null
      probeError = cause instanceof Error ? cause.message : String(cause)
    } finally {
      probing = false
    }
  }

  let tab = $state('body')
  let saveStatus = $state('')
  let saveStatusTimer: number | undefined

  const cookies = $derived(response ? parseCookies(response.headers) : [])
  const extension = $derived(extensionFor(response?.body.contentType))
  const suggestedFile = $derived(`${suggestedName}${extension}`)

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

  async function save(): Promise<void> {
    if (!response) {
      return
    }
    const payload =
      response.body.textual || response.body.content != null
        ? { suggestedName: suggestedFile, text: response.body.content ?? '' }
        : { suggestedName: suggestedFile, base64: response.body.base64 ?? '' }
    try {
      const path = await window.ping.saveResponse(payload)
      showSave(path ? `Saved to ${path}` : 'Save cancelled')
    } catch (cause) {
      showSave(cause instanceof Error ? cause.message : String(cause))
    }
  }

  function showSave(message: string): void {
    saveStatus = message
    window.clearTimeout(saveStatusTimer)
    saveStatusTimer = window.setTimeout(() => (saveStatus = ''), 2500)
  }

  /** Best-effort extension for the save dialog; the user can always change it there. */
  function extensionFor(contentType: string | null | undefined): string {
    const type = (contentType ?? '').toLowerCase()
    if (type.includes('json')) return '.json'
    if (type.includes('html')) return '.html'
    if (type.includes('xml')) return '.xml'
    if (type.includes('pdf')) return '.pdf'
    if (type.includes('png')) return '.png'
    if (type.includes('gif')) return '.gif'
    if (type.includes('jpeg') || type.includes('jpg')) return '.jpg'
    if (type.includes('svg')) return '.svg'
    if (type.includes('webp')) return '.webp'
    if (type.includes('zip')) return '.zip'
    if (type.startsWith('text/')) return '.txt'
    return ''
  }
</script>

<section
  data-role="response"
  aria-busy={inFlight}
  class="flex min-h-0 flex-col overflow-hidden rounded-lg border border-line bg-panel"
>
  {#if response}
    {#if response.assertions && response.assertions.length > 0}
      <ResponseAssertions results={response.assertions} />
    {/if}

    {#if response.captured && response.captured.length > 0}
      <ResponseCaptures results={response.captured} />
    {/if}

    <Tabs tabs={tabs} bind:active={tab} idPrefix="response">
      {#snippet trailing()}
        <!--
          The response's vitals share the tab strip's row rather than taking one of their own:
          the panel is short, and a second full-width band costs it a line of body.
        -->
        <header class="flex min-w-0 items-center gap-4 text-sm">
          <span class="font-mono text-base font-semibold {statusTone(response.status)}">
            {response.status}
          </span>
          <!-- A sibling, not a child: the first header span is the bare status code. -->
          {#if reasonPhrase(response.status)}
            <span class="-ml-2.5 shrink-0 text-fg">{reasonPhrase(response.status)}</span>
          {/if}
          <span class="shrink-0 text-fg-muted">{versionLabel(response.httpVersion)}</span>
          <span class="shrink-0 text-fg-muted">{formatBytes(response.body.bytes)}</span>
          <span class="shrink-0 text-fg-muted">{formatDuration(response.timing.totalMs)}</span>
          {#if response.redirects.length > 0}
            <span class="shrink-0 text-fg-muted">
              followed {response.redirects.length}
              {response.redirects.length === 1 ? 'redirect' : 'redirects'}
            </span>
          {/if}
          {#if response.body.contentType}
            <span class="truncate text-fg-faint">{response.body.contentType}</span>
          {/if}
          <div class="relative flex shrink-0 items-center">
            {#if saveStatus}
              <span
                role="status"
                data-role="save-status"
                class="pointer-events-none absolute right-0 top-full z-10 mt-1 max-w-72 truncate
                       whitespace-nowrap rounded-md border border-line bg-panel px-2 py-1 text-xs
                       text-fg-muted shadow-lg"
              >
                {saveStatus}
              </span>
            {/if}
            <button
              type="button"
              onclick={() => void save()}
              aria-label="Save response body"
              title="Save response body"
              class="rounded-md p-1 text-fg-faint transition hover:bg-line/60 hover:text-fg"
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
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
            </button>
          </div>
        </header>
      {/snippet}
    </Tabs>

    <div
      id="response-panel"
      role="tabpanel"
      aria-labelledby={`response-tab-${tab}`}
      class="min-h-0 flex-1"
    >
      {#if tab === 'body'}
        <ResponseBody {response} {events} />
      {:else if tab === 'headers'}
        <ResponseHeaders headers={response.headers} />
      {:else if tab === 'cookies'}
        <ResponseCookies {cookies} />
      {:else}
        <ResponseTiming
          timing={response.timing}
          bytes={response.body.bytes}
          origin={response.origin}
          {probe}
          {probeError}
          {probing}
          onProbe={() => void runProbe()}
        />
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
