<script lang="ts">
  import type { HistoryEntry } from '../../../shared/history'
  import { formatDuration } from '../lib/format'

  interface Props {
    entries: HistoryEntry[]
    onSelect: (entry: HistoryEntry) => void
  }

  let { entries, onSelect }: Props = $props()

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
    if (entry.outcome === 'error' || (entry.status ?? 0) >= 400) return 'text-red-400'
    return 'text-emerald-400'
  }

  function label(entry: HistoryEntry): string {
    if (entry.outcome === 'cancelled') return 'cancelled'
    if (entry.outcome === 'error' || entry.status === null) return 'failed'
    return String(entry.status)
  }
</script>

<div class="flex-1 overflow-auto py-1" data-role="history">
  {#if entries.length === 0}
    <p class="px-3 py-6 text-sm text-fg-faint">No requests sent yet.</p>
  {:else}
    {#each entries as entry (entry.id)}
      <button
        type="button"
        data-role="history-entry"
        data-url={entry.url}
        onclick={() => onSelect(entry)}
        title={entry.url}
        class="group flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm
               transition hover:bg-line/50"
      >
        <span class="w-9 shrink-0 font-mono text-[10px] uppercase text-fg-faint">
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
