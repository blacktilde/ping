<script lang="ts">
  import type { Snippet } from 'svelte'

  interface Tab {
    id: string
    label: string
    badge?: string | null
  }

  interface Props {
    tabs: Tab[]
    active?: string
    idPrefix: string
    onSelect?: (id: string) => void
    /** Rendered at the end of the strip, outside the tablist, sharing its row. */
    trailing?: Snippet
  }

  let { tabs, active = $bindable(''), idPrefix, onSelect, trailing }: Props = $props()

  function select(id: string): void {
    active = id
    onSelect?.(id)
  }

  // Roving tabindex: only the selected tab is tabbable, and arrows move between them.
  function onKeydown(event: KeyboardEvent, index: number): void {
    const { key } = event
    let next: number
    if (key === 'ArrowRight' || key === 'ArrowDown') {
      next = (index + 1) % tabs.length
    } else if (key === 'ArrowLeft' || key === 'ArrowUp') {
      next = (index - 1 + tabs.length) % tabs.length
    } else if (key === 'Home') {
      next = 0
    } else if (key === 'End') {
      next = tabs.length - 1
    } else {
      return
    }

    event.preventDefault()
    const target = tabs[next]
    select(target.id)
    document.getElementById(`${idPrefix}-tab-${target.id}`)?.focus()
  }
</script>

<!--
  px-5 is the card inset both the request and the response editor use: a tab is flush with
  its label so the underline is exactly the width of the word, and the strip's rule still
  runs the full width of the card. The rule sits on the wrapper rather than the tablist so
  trailing content can share the row without joining the tablist.
-->
<div class="flex items-center gap-4 border-b border-line px-5">
  <div role="tablist" class="flex shrink-0 items-center gap-7">
    {#each tabs as tab, index (tab.id)}
      <button
        id={`${idPrefix}-tab-${tab.id}`}
        type="button"
        role="tab"
        aria-selected={active === tab.id}
        aria-controls={`${idPrefix}-panel`}
        tabindex={active === tab.id ? 0 : -1}
        onclick={() => select(tab.id)}
        onkeydown={(event) => onKeydown(event, index)}
        class="-mb-px flex items-center gap-1.5 border-b-2 py-3.5 text-sm transition
               {active === tab.id
          ? 'border-accent font-medium text-fg'
          : 'border-transparent text-fg-muted hover:text-fg'}"
      >
        {tab.label}
        {#if tab.badge}
          <span class="rounded-full bg-line px-1.5 py-0.5 text-[10px] text-fg-muted">{tab.badge}</span>
        {/if}
      </button>
    {/each}
  </div>
  {#if trailing}
    <div class="flex min-w-0 flex-1 justify-end">{@render trailing()}</div>
  {/if}
</div>
