<script lang="ts">
  import type { HistoryEntry } from '../../../shared/history'
  import { formatDuration, methodTone } from '../lib/format'

  interface Props {
    entries: HistoryEntry[]
    onSelect: (entry: HistoryEntry) => void
  }

  let { entries, onSelect }: Props = $props()

  let query = $state('')

  const filtered = $derived.by(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) {
      return entries
    }
    return entries.filter((entry) =>
      `${entry.name} ${entry.method} ${entry.url}`.toLowerCase().includes(needle)
    )
  })

  /** Coarse relative time; a history list only needs to distinguish recent from old. */
  function when(at: number): string {
    const seconds = Math.max(0, Math.round((Date.now() - at) / 1000))
    if (seconds < 60) return 'just now'
    const minutes = Math.round(seconds / 60)
    if (minutes < 60) return `${minutes}m ago`
    const hours = Math.round(minutes / 60)
    if (hours < 24) return `${hours}h ago`
    return `${Math.round(hours / 24)}d ago`
  }

  function tone(entry: HistoryEntry): string {
    if (entry.outcome === 'cancelled') return 'text-fg-faint'
    if (entry.outcome === 'error' || (entry.status ?? 0) >= 400) return 'text-danger'
    return 'text-success'
  }

  function label(entry: HistoryEntry): string {
    if (entry.outcome === 'cancelled') return 'cancelled'
    if (entry.outcome === 'error' || entry.status === null) return 'failed'
    return String(entry.status)
  }
</script>

<div class="flex min-h-0 flex-1 flex-col" data-role="history">
  <div class="border-b border-line px-2 py-1.5">
    <input
      bind:value={query}
      type="search"
      aria-label="Search history"
      placeholder="Search history"
      spellcheck="false"
      autocomplete="off"
      class="w-full rounded-md border border-line bg-base px-2 py-1 text-xs text-fg outline-none
             transition placeholder:text-fg-faint focus:border-accent focus:ring-3 focus:ring-accent/15"
    />
  </div>
  <div class="flex-1 overflow-auto py-1">
    {#if entries.length === 0}
      <p class="px-3 py-6 text-sm text-fg-faint">No requests sent yet.</p>
    {:else if filtered.length === 0}
      <p class="px-3 py-6 text-sm text-fg-faint">No matching requests.</p>
    {:else}
      {#each filtered as entry (entry.id)}
        <button
          type="button"
          data-role="history-entry"
          data-url={entry.url}
          onclick={() => onSelect(entry)}
          title={entry.url}
          class="group flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm
                 transition hover:bg-line/50"
        >
          <span class="w-12 shrink-0 font-mono text-[10px] uppercase {methodTone(entry.method)}">
            {entry.method}
          </span>
          <span class="min-w-0 flex-1">
            <span class="block truncate text-fg-muted group-hover:text-fg">{entry.name}</span>
            <span class="block truncate text-[11px] text-fg-faint">{entry.url}</span>
          </span>
          <span class="flex shrink-0 flex-col items-end text-[11px]">
            <span class="font-mono {tone(entry)}">{label(entry)}</span>
            <span class="text-fg-faint">
              {entry.durationMs === null ? when(entry.at) : formatDuration(entry.durationMs)}
            </span>
          </span>
        </button>
      {/each}
    {/if}
  </div>
</div>
