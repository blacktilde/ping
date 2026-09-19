<script lang="ts">
  import { draftKey } from '../lib/store'
  import type { RequestTab } from '../lib/tabs.svelte'

  interface Props {
    tabs: RequestTab[]
    activeId: string
    onActivate: (id: string) => void
    onClose: (id: string) => void
    onNew: () => void
  }

  let { tabs, activeId, onActivate, onClose, onNew }: Props = $props()

  // The tab whose name is being edited inline, if any.
  let renaming = $state<string | null>(null)
  let renameValue = $state('')
  let renameInput = $state<HTMLInputElement>()

  /** A tab's unsaved state, so the strip can hint at losing work. */
  function dirty(tab: RequestTab): boolean {
    return tab.savedKey !== null && tab.savedKey !== draftKey(tab.draft)
  }

  function startRename(tab: RequestTab): void {
    onActivate(tab.id)
    renaming = tab.id
    renameValue = tab.draft.name
  }

  function commitRename(tab: RequestTab): void {
    const trimmed = renameValue.trim()
    // An empty name would leave the tab unlabelled; keep the old one instead.
    if (trimmed) {
      tab.draft.name = trimmed
    }
    renaming = null
  }

  function onRenameKeydown(event: KeyboardEvent, tab: RequestTab): void {
    if (event.key === 'Enter') {
      event.preventDefault()
      commitRename(tab)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      renaming = null
    }
  }

  $effect(() => {
    if (renaming) {
      queueMicrotask(() => {
        renameInput?.focus()
        renameInput?.select()
      })
    }
  })

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
    onActivate(target.id)
    document.getElementById(`request-tab-${target.id}`)?.focus()
  }
</script>

<div
  role="tablist"
  aria-label="Open requests"
  class="flex items-stretch gap-1 overflow-x-auto border-b border-line px-2"
>
  {#each tabs as tab, index (tab.id)}
    {@const active = tab.id === activeId}
    <div
      class="group flex shrink-0 items-center border-b-2 transition
             {active ? 'border-accent' : 'border-transparent'}"
    >
      {#if renaming === tab.id}
        <span class="flex items-center gap-1.5 py-1 pl-3 text-sm">
          <span class="font-mono text-[10px] uppercase text-fg-faint">{tab.draft.method}</span>
          <input
            bind:this={renameInput}
            bind:value={renameValue}
            onkeydown={(event) => onRenameKeydown(event, tab)}
            onblur={() => commitRename(tab)}
            aria-label="Request name"
            class="w-40 rounded border border-accent bg-base px-1.5 py-0.5 text-sm text-fg
                   outline-none"
          />
        </span>
      {:else}
        <button
          type="button"
          role="tab"
          id={`request-tab-${tab.id}`}
          aria-selected={active}
          aria-controls="request-tabpanel"
          tabindex={active ? 0 : -1}
          data-role="request-tab"
          data-path={tab.path ?? ''}
          onclick={() => onActivate(tab.id)}
          ondblclick={() => startRename(tab)}
          onkeydown={(event) => onKeydown(event, index)}
          title={tab.path ?? tab.draft.name}
          class="flex items-center gap-1.5 py-2 pl-3 text-sm transition
                 {active ? 'text-fg' : 'text-fg-muted hover:text-fg'}"
        >
          <span class="font-mono text-[10px] uppercase text-fg-faint">{tab.draft.method}</span>
          <span class="max-w-40 truncate">{tab.draft.name || 'Untitled request'}</span>
          {#if dirty(tab)}
            <span
              data-role="tab-dirty"
              title="Unsaved changes"
              class="h-1.5 w-1.5 shrink-0 rounded-full bg-accent"
            ></span>
          {/if}
        </button>
      {/if}
      <button
        type="button"
        onclick={() => onClose(tab.id)}
        aria-label="Close {tab.draft.name || 'request'}"
        class="mr-1 rounded px-1 text-base leading-none text-fg-faint transition
               hover:bg-line/60 hover:text-fg"
      >
        ×
      </button>
    </div>
  {/each}

  <button
    type="button"
    onclick={onNew}
    aria-label="New request tab"
    title="New request tab (⌘T)"
    class="my-1 shrink-0 rounded-md px-2 text-lg leading-none text-fg-faint transition
           hover:bg-line/60 hover:text-fg"
  >
    +
  </button>
</div>
