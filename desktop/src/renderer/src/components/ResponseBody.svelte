<script lang="ts">
  import type { HttpResponse } from '../lib/http'
  import { isHtml, prettyJson } from '../lib/response'
  import { copyText } from '../lib/clipboard'
  import { formatBytes } from '../lib/format'
  import { isEventStream, type SseEvent } from '../lib/sse'
  import CodeEditor from './CodeEditor.svelte'
  import ResponseEvents from './ResponseEvents.svelte'

  interface Props {
    response: HttpResponse
    /** Events parsed as a server-sent stream arrived. */
    events?: SseEvent[]
  }

  type View = 'events' | 'pretty' | 'raw' | 'preview'

  let { response, events = [] }: Props = $props()

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

  const raw = $derived(response.body.content ?? '')
  const parsed = $derived(response.body.textual ? prettyJson(raw) : null)
  const pretty = $derived(parsed ?? raw)
  const hasPreview = $derived(isHtml(response.body.contentType) || hasImageType(response.body.contentType))
  const image = $derived(
    hasImageType(response.body.contentType) && response.body.base64 != null
  )
  const imageSrc = $derived(
    image ? `data:${response.body.contentType};base64,${response.body.base64}` : null
  )
  const looksJson = $derived((response.body.contentType ?? '').toLowerCase().includes('json'))
  // Pretty falls back to raw; say so rather than silently showing unformatted text.
  const invalidJson = $derived(
    looksJson && response.body.textual && raw.trim().length > 0 && parsed === null
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
  <div class="flex items-center gap-1 border-b border-line px-3 py-1.5">
    <div role="radiogroup" aria-label="Response body view" class="flex items-center gap-1">
      {#each views as option (option)}
        <button
          type="button"
          role="radio"
          aria-checked={view === option}
          disabled={!enabled(option)}
          title={option === 'preview' && !hasPreview
            ? 'Preview is available for HTML and image responses'
            : undefined}
          tabindex={view === option ? 0 : -1}
          onclick={() => (view = option)}
          onkeydown={onKeydown}
          class="rounded-md px-2.5 py-1 text-xs capitalize transition disabled:cursor-not-allowed
                 disabled:opacity-30
                 {view === option
            ? 'bg-line text-fg'
            : 'text-fg-muted hover:text-fg'}"
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
          class="rounded-md px-2 py-1 text-xs text-fg-muted transition hover:bg-line/60
                 hover:text-fg"
        >
          {copied === 'done' ? 'Copied' : copied === 'failed' ? 'Copy failed' : 'Copy'}
        </button>
      {/if}
    </span>
  </div>

  {#if response.body.truncated}
    <p class="border-b border-warning-soft bg-warning-soft px-4 py-2 text-xs text-warning">
      {live
        ? 'The display cap has been reached: newer data is still arriving but is not shown.'
        : 'Response is larger than the display cap; only the beginning is shown.'}
    </p>
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
    {:else if !response.body.textual}
      <div class="flex h-full flex-col items-center justify-center gap-3 text-sm text-fg-faint">
        <p>
          Binary response{response.body.contentType
            ? ` (${response.body.contentType})`
            : ''} — not displayed.
        </p>
        <p class="text-xs">Use Save in the response header to keep it.</p>
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
          Preview is available for HTML and image responses.
        </div>
      {/if}
    {:else if view === 'events' && eventStream}
      <ResponseEvents {events} content={raw} {live} />
    {:else if view === 'pretty'}
      <CodeEditor value={pretty} language="json" label="Response body, pretty" readonly />
    {:else}
      <CodeEditor value={raw} language="plain" label="Response body, raw" readonly />
    {/if}
  </div>
</div>
