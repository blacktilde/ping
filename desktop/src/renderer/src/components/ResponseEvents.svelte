<script lang="ts">
  import { parseSse, type SseEvent } from '../lib/sse'
  import { formatDuration } from '../lib/format'
  import { prettyJson } from '../lib/response'

  interface Props {
    /** Events parsed as they arrived; empty for a finished stream that never went through the live path. */
    events: SseEvent[]
    /** The body text, used to parse a stream that has no live events. */
    content: string
    live: boolean
  }

  let { events, content, live }: Props = $props()

  const shown = $derived(events.length > 0 ? events : parseSse(content))

  let scroller = $state<HTMLDivElement>()
  let following = $state(true)

  // Stay at the newest event while the user has not scrolled away from it.
  $effect(() => {
    void shown.length
    if (following && scroller) {
      scroller.scrollTop = scroller.scrollHeight
    }
  })

  function onScroll(): void {
    if (!scroller) {
      return
    }
    following = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 24
  }

  function jump(): void {
    following = true
    if (scroller) {
      scroller.scrollTop = scroller.scrollHeight
    }
  }

  /** JSON data reads better formatted; anything else is shown as sent. */
  function body(data: string): string {
    return prettyJson(data) ?? data
  }
</script>

<div class="relative h-full">
  <div
    bind:this={scroller}
    onscroll={onScroll}
    data-role="sse-events"
    class="h-full overflow-auto"
  >
    {#if shown.length === 0}
      <p class="px-5 py-6 text-sm text-fg-faint">
        {live ? 'Waiting for the first event…' : 'The stream sent no events.'}
      </p>
    {:else}
      <ol>
        {#each shown as event (event.index)}
          <li data-role="sse-event" class="border-b border-line px-5 py-2">
            <div class="flex flex-wrap items-center gap-2 text-xs text-fg-muted">
              <span class="font-mono text-fg-faint">#{event.index}</span>
              <span class="font-mono">+{formatDuration(event.atMs)}</span>
              {#if event.event}
                <span class="rounded bg-line px-1.5 py-0.5 font-mono text-fg">{event.event}</span>
              {/if}
              {#if event.id}
                <span class="font-mono text-fg-faint">id {event.id}</span>
              {/if}
            </div>
            <pre
              data-role="sse-data"
              class="mt-1 whitespace-pre-wrap break-words font-mono text-sm text-fg">{body(event.data)}</pre>
          </li>
        {/each}
      </ol>
    {/if}
  </div>

  {#if live && !following}
    <button
      type="button"
      onclick={jump}
      class="absolute bottom-3 right-4 rounded-full border border-line bg-panel px-3 py-1 text-xs
             text-fg-muted shadow transition hover:text-fg"
    >
      Jump to latest
    </button>
  {/if}
</div>
