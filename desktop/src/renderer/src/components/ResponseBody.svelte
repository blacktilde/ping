<script lang="ts">
  import type { HttpResponse } from '../lib/http'
  import { FORMAT_OFF_THREAD_FROM, formatJson } from '../lib/pretty'
  import {
    bytesFromBase64,
    DEFAULT_DISPLAY_CAP,
    isHtml,
    isPdf,
    MAX_OFFERED_CAP,
    prettyJson,
    raisedCap
  } from '../lib/response'
  import { copyText } from '../lib/clipboard'
  import { formatBytes } from '../lib/format'
  import { isEventStream, type SseEvent } from '../lib/sse'
  import CodeEditor from './CodeEditor.svelte'
  import ResponseEvents from './ResponseEvents.svelte'

  interface Props {
    response: HttpResponse
    /** Events parsed as a server-sent stream arrived. */
    events?: SseEvent[]
    /** The display cap the response was read under. */
    cap?: number
    /** Sends the request again with a larger display cap; absent while a send is in flight. */
    onResend?: (maxBodyBytes: number) => void
  }

  type View = 'events' | 'pretty' | 'raw' | 'preview'

  let { response, events = [], cap = DEFAULT_DISPLAY_CAP, onResend }: Props = $props()

  const eventStream = $derived(isEventStream(response.body.contentType))
  // Arriving now: the head is in, the stream has not ended.
  const live = $derived(response.streamed === true && response.ended === undefined)
  const streamNote = $derived.by(() => {
    if (!response.streamed) return ''
    const size = formatBytes(response.body.bytes)
    const count = eventStream ? ` · ${events.length} event${events.length === 1 ? '' : 's'}` : ''
    switch (response.ended) {
      case undefined:
        return `Streaming${count} · ${size}`
      case 'cancelled':
        return `Stopped${count} · ${size}`
      case 'closed':
        return `Closed by the server${count} · ${size}`
      case 'timeout':
        return `Read until the timeout${count} · ${size}`
      default:
        return `The connection broke${count} · ${size}`
    }
  })

  let view = $state<View>('pretty')

  // A feed is not re-fetched from here: it would start again rather than pick up where it was cut.
  const kept = $derived(formatBytes(Math.min(cap, response.body.bytes)))
  const nextCap = $derived(
    response.body.truncated && !response.streamed ? raisedCap(response.body.bytes, cap) : null
  )

  const raw = $derived(response.body.content ?? '')
  // A body still arriving is rarely valid JSON yet, and formatting it on every chunk would
  // re-parse the whole body each time; it is formatted once the stream ends.
  const formattable = $derived(response.body.textual && !live)
  const offThread = $derived(raw.length >= FORMAT_OFF_THREAD_FROM)
  const parsedInPlace = $derived(formattable && !offThread ? prettyJson(raw) : null)
  // A large body is formatted in a worker; the result is kept with the text it came from, so
  // an answer that arrives after the body changed is never shown against the new one.
  let formatted = $state<{ source: string; text: string | null } | null>(null)
  $effect(() => {
    if (!formattable || !offThread) return
    const source = raw
    let current = true
    void formatJson(source).then((text) => {
      if (current) formatted = { source, text }
    })
    return () => {
      current = false
    }
  })
  const formatting = $derived(formattable && offThread && formatted?.source !== raw)
  const parsed = $derived(
    !formattable ? null : offThread ? (formatting ? null : formatted!.text) : parsedInPlace
  )
  const pretty = $derived(parsed ?? raw)
  const hasPreview = $derived(
    isHtml(response.body.contentType)
      || hasImageType(response.body.contentType)
      || isPdf(response.body.contentType)
  )
  const image = $derived(
    hasImageType(response.body.contentType) && response.body.base64 != null
  )
  const imageSrc = $derived(
    image ? `data:${response.body.contentType};base64,${response.body.base64}` : null
  )
  // A truncated PDF has no cross-reference table, so the viewer would only show an error.
  const pdf = $derived(
    isPdf(response.body.contentType) && response.body.base64 != null && !response.body.truncated
  )
  let pdfSrc = $state<string | null>(null)
  const looksJson = $derived((response.body.contentType ?? '').toLowerCase().includes('json'))
  // Pretty falls back to raw; say so rather than silently showing unformatted text.
  const invalidJson = $derived(
    looksJson && formattable && !formatting && raw.trim().length > 0 && parsed === null
  )

  const views = $derived<View[]>(
    eventStream ? ['events', 'pretty', 'raw'] : ['pretty', 'raw', 'preview']
  )

  function enabled(option: View): boolean {
    return option === 'preview' ? hasPreview : option === 'events' ? eventStream : true
  }

  let copied = $state<'' | 'done' | 'failed'>('')
  let copiedTimer: number | undefined

  async function copyBody(): Promise<void> {
    try {
      await copyText(view === 'raw' ? raw : pretty)
      copied = 'done'
    } catch {
      copied = 'failed'
    }
    window.clearTimeout(copiedTimer)
    copiedTimer = window.setTimeout(() => (copied = ''), 1500)
  }

  function move(delta: number): void {
    const available = views.filter(enabled)
    const index = available.indexOf(view)
    view = available[((index === -1 ? 0 : index) + delta + available.length) % available.length]
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      event.preventDefault()
      move(1)
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      event.preventDefault()
      move(-1)
    }
  }

  function hasImageType(contentType: string | null | undefined): boolean {
    return (contentType ?? '').toLowerCase().startsWith('image/')
  }

  // The object URL is the viewer's handle on the bytes; it has to be released when the
  // response changes or every send would leak a copy of the document.
  $effect(() => {
    const base64 = pdf ? response.body.base64 : null
    if (base64 == null) {
      pdfSrc = null
      return
    }
    const url = URL.createObjectURL(
      new Blob([bytesFromBase64(base64)], { type: 'application/pdf' })
    )
    pdfSrc = url
    return () => {
      URL.revokeObjectURL(url)
      pdfSrc = null
    }
  })

  // Pretty and raw persist across sends; a view that no longer applies (preview on a
  // non-HTML body) falls back rather than showing the previous response's mode.
  $effect(() => {
    if (!enabled(view)) {
      view = 'pretty'
    }
  })

  // A feed of events is read as events; when the response stops being one, fall back.
  let wasEventStream = false
  $effect(() => {
    if (eventStream && !wasEventStream) {
      view = 'events'
    }
    wasEventStream = eventStream
  })
</script>

<div class="flex h-full flex-col">
  <!-- py-2 to the tab strip above it: a row of controls, not a band of its own. -->
  <div class="flex items-center gap-3 px-5 py-2">
    <!-- One bordered group with hairline separators; the chosen view is the brighter label. -->
    <div
      role="radiogroup"
      aria-label="Response body view"
      class="flex items-center divide-x divide-line overflow-hidden rounded-lg border border-line"
    >
      {#each views as option (option)}
        <button
          type="button"
          role="radio"
          aria-checked={view === option}
          disabled={!enabled(option)}
          title={option === 'preview' && !hasPreview
            ? 'Preview is available for HTML, image and PDF responses'
            : undefined}
          tabindex={view === option ? 0 : -1}
          onclick={() => (view = option)}
          onkeydown={onKeydown}
          class="px-3.5 py-1.5 text-sm capitalize transition disabled:cursor-not-allowed
                 disabled:opacity-30
                 {view === option ? 'font-medium text-fg' : 'text-fg-muted hover:text-fg'}"
        >
          {option}
        </button>
      {/each}
    </div>

    <span class="ml-auto flex items-center gap-2 text-xs text-fg-faint">
      {#if streamNote}
        <span data-role="stream-note" data-live={live} class="flex items-center gap-1.5 {live ? 'text-fg-muted' : ''}">
          {#if live}<span class="h-1.5 w-1.5 animate-pulse rounded-full bg-success"></span>{/if}
          {streamNote}
        </span>
      {/if}
      {#if !response.body.textual}
        Binary response
      {:else if invalidJson}
        Not valid JSON; showing the raw text
      {/if}
      {#if response.body.textual}
        <button
          type="button"
          onclick={() => void copyBody()}
          aria-label="Copy response body"
          title="Copy response body"
          class="text-sm font-medium text-accent transition hover:brightness-125"
        >
          {copied === 'done' ? 'Copied' : copied === 'failed' ? 'Copy failed' : 'Copy'}
        </button>
      {/if}
    </span>
  </div>

  {#if response.body.truncated}
    <div
      data-role="truncated"
      class="flex flex-wrap items-center gap-x-3 gap-y-1 border-y border-warning-soft bg-warning-soft
             px-5 py-2 text-xs text-warning"
    >
      <p class="min-w-0 flex-1">
        {#if live}
          The display cap ({kept}) has been reached: newer data is still arriving but is not shown.
        {:else}
          Showing the first {kept} of {formatBytes(response.body.bytes)}; the rest was not kept.
          {#if !response.streamed && nextCap === null}
            That is more than {formatBytes(MAX_OFFERED_CAP)}, too large to display here.
          {/if}
        {/if}
      </p>
      {#if nextCap !== null && onResend}
        <button
          type="button"
          data-role="resend-with-cap"
          onclick={() => onResend(nextCap)}
          title="Send the request again, keeping up to {formatBytes(nextCap)} of the body. The request's saved settings do not change."
          class="shrink-0 rounded-md border border-warning/40 px-2 py-0.5 font-medium transition
                 hover:bg-warning/10"
        >
          Resend with a {formatBytes(nextCap)} cap
        </button>
      {/if}
    </div>
  {/if}

  <div class="min-h-0 flex-1">
    {#if !response.body.textual && imageSrc}
      <div class="flex h-full items-center justify-center overflow-auto p-4">
        <img
          src={imageSrc}
          alt="Response preview"
          class="max-h-full max-w-full rounded-md border border-line"
        />
      </div>
    {:else if !response.body.textual && pdfSrc}
      <!--
        Chromium's own viewer, in a process of its own, given the bytes as a blob so the
        document never reaches disk. Chromium loads it as plugin data and then frames the
        viewer, so the CSP in index.html has to allow blob: to both `object-src` and
        `frame-src`; allowing one leaves a blank pane and a console violation.
      -->
      <embed
        title="Response preview"
        src={pdfSrc}
        type="application/pdf"
        class="h-full w-full border-0 bg-white"
      />
    {:else if !response.body.textual}
      <div class="flex h-full flex-col items-center justify-center gap-3 text-sm text-fg-faint">
        <p>
          Binary response{response.body.contentType
            ? ` (${response.body.contentType})`
            : ''} — not displayed.
        </p>
        <p class="text-xs">
          {#if isPdf(response.body.contentType) && response.body.truncated}
            An incomplete document cannot be previewed. Use Save in the response header to keep
            what was read.
          {:else}
            Use Save in the response header to keep it.
          {/if}
        </p>
      </div>
    {:else if view === 'preview'}
      {#if hasPreview}
        <iframe
          title="Response preview"
          sandbox=""
          srcdoc={raw}
          class="h-full w-full border-0 bg-white"
        ></iframe>
      {:else}
        <div class="flex h-full items-center justify-center text-sm text-fg-faint">
          Preview is available for HTML, image and PDF responses.
        </div>
      {/if}
    {:else if view === 'events' && eventStream}
      <ResponseEvents {events} content={raw} {live} />
    {:else if view === 'pretty' && formatting}
      <!-- Not the raw text in the meantime: building a large document only to replace it
           a moment later would cost as much as the formatting saved. -->
      <div
        data-role="formatting"
        class="flex h-full items-center justify-center text-sm text-fg-faint"
      >
        Formatting {formatBytes(response.body.bytes)}…
      </div>
    {:else if view === 'pretty'}
      <CodeEditor value={pretty} language="json" label="Response body, pretty" readonly pad="px-5" />
    {:else}
      <CodeEditor value={raw} language="plain" label="Response body, raw" readonly pad="px-5" />
    {/if}
  </div>
</div>
