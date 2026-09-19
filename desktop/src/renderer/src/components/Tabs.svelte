<script lang="ts">
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
  }

  let { tabs, active = $bindable(''), idPrefix, onSelect }: Props = $props()

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

<div role="tablist" class="flex items-center gap-1 border-b border-line px-2">
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
      class="flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm transition
             {active === tab.id
        ? 'border-accent text-fg'
        : 'border-transparent text-fg-muted hover:text-fg'}"
    >
      {tab.label}
      {#if tab.badge}
        <span class="rounded-full bg-line px-1.5 text-[10px] text-fg-muted">{tab.badge}</span>
      {/if}
    </button>
  {/each}
</div>
