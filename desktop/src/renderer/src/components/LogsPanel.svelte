<script lang="ts">
  import { tick } from 'svelte'
  import { copyText } from '../lib/clipboard'
  import type { LogEntry } from '../../../shared/logs'

  interface Props {
    onClose: () => void
  }

  let { onClose }: Props = $props()

  /** Matches the main process's buffer, so a long-open view holds no more than a reopened one. */
  const CAPACITY = 2000

  let entries = $state<LogEntry[]>([])
  let source = $state('all')
  let query = $state('')
  let status = $state('')
  let error = $state('')
  let list = $state<HTMLElement | null>(null)
  // Follows new lines only while the view is scrolled to the bottom, so reading older ones
  // is not interrupted.
  let following = true

  const sources = $derived([...new Set(entries.map((entry) => entry.source))].sort())
  const shown = $derived.by(() => {
    const needle = query.trim().toLowerCase()
    return entries.filter(
      (entry) =>
        (source === 'all' || entry.source === source) &&
        (!needle || entry.text.toLowerCase().includes(needle))
    )
  })

  $effect(() => {
    let last = 0
    const add = (incoming: LogEntry[]): void => {
      const fresh = incoming.filter((entry) => entry.seq > last)
      if (fresh.length === 0) {
        return
      }
      last = fresh[fresh.length - 1].seq
      const next = [...entries, ...fresh]
      entries = next.length > CAPACITY ? next.slice(next.length - CAPACITY) : next
      void scrollIfFollowing()
    }
    // Subscribe before listing, so nothing written in between is lost; `seq` drops the overlap.
    const pending: LogEntry[] = []
    let listed = false
    const off = window.ping.logs.onEntry((entry) => (listed ? add([entry]) : pending.push(entry)))
    void window.ping.logs.list().then((initial) => {
      add(initial)
      add(pending)
      listed = true
    })
    return off
  })

  async function scrollIfFollowing(): Promise<void> {
    if (!following) {
      return
    }
    await tick()
    list?.scrollTo({ top: list.scrollHeight })
  }

  function onScroll(): void {
    if (list) {
      following = list.scrollHeight - list.scrollTop - list.clientHeight < 24
    }
  }

  function time(entry: LogEntry): string {
    const date = new Date(entry.time)
    const pad = (value: number, width = 2): string => String(value).padStart(width, '0')
    return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`
  }

  function flash(message: string): void {
    status = message
    setTimeout(() => {
      if (status === message) {
        status = ''
      }
    }, 2000)
  }

  function plainError(cause: unknown): string {
    // Electron prefixes the main-process error; the sentence after it is the useful part.
    const message = cause instanceof Error ? cause.message : String(cause)
    return message.replace(/^Error invoking remote method '[^']*': (Error: )?/, '')
  }

  async function copy(): Promise<void> {
    error = ''
    const text = shown
      .map((entry) => `${new Date(entry.time).toISOString()} [${entry.source}] ${entry.text}`)
      .join('\n')
    try {
      await copyText(text)
      flash(`Copied ${shown.length} ${shown.length === 1 ? 'line' : 'lines'}`)
    } catch (cause) {
      error = plainError(cause)
    }
  }

  async function openFolder(): Promise<void> {
    error = ''
    try {
      await window.ping.logs.openFolder()
    } catch (cause) {
      error = plainError(cause)
    }
  }

  async function clear(): Promise<void> {
    error = ''
    await window.ping.logs.clear()
    entries = []
  }

</script>

<!-- Docked along the bottom of the window, under everything; the status bar toggles it. -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<aside
  data-role="logs-panel"
  aria-label="Logs"
  onkeydown={(event) => {
    if (event.key === 'Escape') onClose()
  }}
  class="flex h-full w-full flex-col border-t border-line bg-panel"
>
  <div class="flex items-center gap-2 border-b border-line px-3 py-1.5">
    <span class="rounded-md bg-line px-2 py-1 text-xs font-medium uppercase tracking-wide text-fg">
      Logs
    </span>
    <span class="text-xs text-fg-faint" data-role="logs-count">
      {shown.length === entries.length
        ? `${entries.length} ${entries.length === 1 ? 'line' : 'lines'}`
        : `${shown.length} of ${entries.length} lines`}
    </span>
    {#if error}
      <span data-role="logs-error" role="alert" class="min-w-0 truncate text-xs text-danger">{error}</span>
    {:else if status}
      <span data-role="logs-status" role="status" class="text-xs text-success">{status}</span>
    {/if}

    <div class="ml-auto flex items-center gap-2">
      <select
        data-role="logs-source"
        bind:value={source}
        aria-label="Source"
        class="rounded-md border border-line bg-base px-2 py-1 text-xs outline-none
               transition focus:border-accent focus:ring-3 focus:ring-accent/15"
      >
        <option value="all">All sources</option>
        {#each sources as name (name)}
          <option value={name}>{name}</option>
        {/each}
      </select>
      <input
        data-role="logs-filter"
        bind:value={query}
        aria-label="Filter"
        placeholder="Filter"
        autocomplete="off"
        spellcheck="false"
        class="w-48 rounded-md border border-line bg-base px-2 py-1 text-xs outline-none
               transition focus:border-accent focus:ring-3 focus:ring-accent/15"
      />
      <button
        type="button"
        data-role="logs-copy"
        disabled={shown.length === 0}
        onclick={() => void copy()}
        title="Copy the lines shown, with timestamps"
        class="rounded-md border border-line px-2 py-1 text-xs text-fg-muted transition
               hover:border-fg-muted hover:text-fg disabled:opacity-50"
      >
        Copy
      </button>
      <button
        type="button"
        data-role="logs-open-folder"
        onclick={() => void openFolder()}
        class="rounded-md border border-line px-2 py-1 text-xs text-fg-muted transition
               hover:border-fg-muted hover:text-fg"
      >
        Open log folder
      </button>
      <button
        type="button"
        data-role="logs-clear"
        disabled={entries.length === 0}
        onclick={() => void clear()}
        title="Empty this view; the log file keeps everything"
        class="rounded-md border border-line px-2 py-1 text-xs text-fg-muted transition
               hover:border-fg-muted hover:text-fg disabled:opacity-50"
      >
        Clear
      </button>
      <button
        type="button"
        data-role="logs-close"
        onclick={onClose}
        aria-label="Close logs"
        title="Close logs"
        class="rounded-md p-1.5 text-fg-muted transition hover:bg-line/60 hover:text-fg"
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
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
      </button>
    </div>
  </div>

  <div
    bind:this={list}
    onscroll={onScroll}
    data-role="logs-list"
    class="min-h-0 flex-1 overflow-auto bg-base px-3 py-1.5 font-mono text-xs leading-relaxed"
  >
    {#if shown.length === 0}
      <p class="py-4 text-center font-sans text-fg-faint">
        {entries.length === 0 ? 'Nothing has been logged this session.' : 'No lines match.'}
      </p>
    {:else}
      {#each shown as entry (entry.seq)}
        <div data-role="logs-line" class="flex gap-3 whitespace-pre-wrap break-all">
          <span class="shrink-0 text-fg-faint">{time(entry)}</span>
          <span class="w-20 shrink-0 truncate text-accent">{entry.source}</span>
          <span class="min-w-0 flex-1 text-fg">{entry.text}</span>
        </div>
      {/each}
    {/if}
  </div>
</aside>
