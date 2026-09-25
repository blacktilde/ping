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

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
    }
  }
</script>

<svelte:window onkeydown={onKeydown} />

<div class="fixed inset-0 z-50 flex items-center justify-center motion-backdrop">
  <button
    type="button"
    aria-label="Dismiss"
    class="absolute inset-0 h-full w-full cursor-default"
    onclick={onClose}
  ></button>
  <div
    data-role="logs-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="Logs"
    tabindex="-1"
    class="motion-rise relative z-10 flex h-[75vh] w-full max-w-4xl flex-col rounded-xl border
           border-line bg-panel shadow-2xl"
  >
    <header class="border-b border-line px-5 py-4">
      <h2 class="text-sm font-medium text-fg">Logs</h2>
      <p class="mt-1 text-xs leading-relaxed text-fg-faint">
        Diagnostics from the app and its core engine. Secret values and credential headers are
        masked before a line is kept, so what you copy here is safe to attach to a bug report.
      </p>
    </header>

    <div class="flex items-center gap-2 border-b border-line px-5 py-2">
      <select
        data-role="logs-source"
        bind:value={source}
        aria-label="Source"
        class="rounded-md border border-line bg-base px-2 py-1.5 text-xs outline-none
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
        class="min-w-0 flex-1 rounded-md border border-line bg-base px-2 py-1.5 text-xs outline-none
               transition focus:border-accent focus:ring-3 focus:ring-accent/15"
      />
      <button
        type="button"
        data-role="logs-copy"
        disabled={shown.length === 0}
        onclick={() => void copy()}
        class="rounded-md border border-line px-2 py-1.5 text-xs text-fg-muted transition
               hover:border-fg-muted hover:text-fg disabled:opacity-50"
      >
        Copy
      </button>
      <button
        type="button"
        data-role="logs-open-folder"
        onclick={() => void openFolder()}
        class="rounded-md border border-line px-2 py-1.5 text-xs text-fg-muted transition
               hover:border-fg-muted hover:text-fg"
      >
        Open log folder
      </button>
      <button
        type="button"
        data-role="logs-clear"
        disabled={entries.length === 0}
        onclick={() => void clear()}
        class="rounded-md border border-line px-2 py-1.5 text-xs text-fg-muted transition
               hover:border-fg-muted hover:text-fg disabled:opacity-50"
      >
        Clear
      </button>
    </div>

    <div
      bind:this={list}
      onscroll={onScroll}
      data-role="logs-list"
      class="min-h-0 flex-1 overflow-auto bg-base px-5 py-2 font-mono text-xs leading-relaxed"
    >
      {#if shown.length === 0}
        <p class="py-6 text-center font-sans text-fg-faint">
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

    <footer class="flex items-center justify-between gap-2 border-t border-line px-5 py-3 text-xs">
      <span class="text-fg-faint" data-role="logs-count">
        {shown.length === entries.length
          ? `${entries.length} ${entries.length === 1 ? 'line' : 'lines'}`
          : `${shown.length} of ${entries.length} lines`}
      </span>
      {#if error}
        <span data-role="logs-error" role="alert" class="min-w-0 flex-1 truncate text-right text-danger">{error}</span>
      {:else if status}
        <span data-role="logs-status" role="status" class="text-success">{status}</span>
      {/if}
      <button
        type="button"
        data-role="logs-close"
        onclick={onClose}
        class="rounded-lg border border-line px-4 py-2 text-sm text-fg-muted transition
               hover:border-fg-muted hover:text-fg"
      >
        Close
      </button>
    </footer>
  </div>
</div>
