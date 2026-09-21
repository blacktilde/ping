<script lang="ts">
  import { untrack } from 'svelte'
  import { renderMarkdown } from '../lib/markdown'

  interface Props {
    /** The notes; undefined or empty means none. */
    value: string | undefined
    /** Called with the new text; empty text means "no notes". */
    onChange: (value: string) => void
    label: string
    placeholder?: string
  }

  let { value, onChange, label, placeholder = 'Notes in Markdown' }: Props = $props()

  // Notes that already exist open as a preview; an empty field opens ready to type.
  // Read once on purpose: the mode is the user's to switch, not something to follow the value.
  let mode = $state<'edit' | 'preview'>(
    untrack(() => (value && value.trim() ? 'preview' : 'edit'))
  )
  const html = $derived(renderMarkdown(value ?? ''))
</script>

<div class="flex h-full min-h-0 flex-col">
  <div class="flex items-center gap-1 border-b border-line py-1.5">
    <div role="tablist" aria-label="{label} view" class="flex items-center gap-1">
      {#each ['edit', 'preview'] as const as option (option)}
        <button
          type="button"
          role="tab"
          aria-selected={mode === option}
          onclick={() => (mode = option)}
          class="rounded-md px-2 py-1 text-xs font-medium capitalize transition
                 {mode === option ? 'bg-line text-fg' : 'text-fg-muted hover:text-fg'}"
        >
          {option}
        </button>
      {/each}
    </div>
    <span class="ml-auto text-[10px] uppercase tracking-wide text-fg-faint">Markdown</span>
  </div>

  {#if mode === 'edit'}
    <textarea
      value={value ?? ''}
      oninput={(event) => onChange(event.currentTarget.value)}
      aria-label={label}
      {placeholder}
      spellcheck="true"
      class="min-h-0 flex-auto resize-none bg-transparent py-3 font-mono text-sm outline-none"
    ></textarea>
  {:else}
    <div data-role="docs-preview" class="markdown min-h-0 flex-auto overflow-auto py-3 text-sm">
      {#if html}
        <!-- eslint-disable-next-line svelte/no-at-html-tags -- output of renderMarkdown: raw HTML is off and links are validated -->
        {@html html}
      {:else}
        <p class="text-fg-faint">No notes yet. Switch to Edit to add some.</p>
      {/if}
    </div>
  {/if}
</div>
