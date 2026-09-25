<script lang="ts">
  import type { Snippet } from 'svelte'
  import ContextMenu, { type MenuItem } from './ContextMenu.svelte'
  import { methodTone } from '../lib/format'
  import { draftKey } from '../lib/store'
  import type { RequestTab } from '../lib/tabs.svelte'

  interface Props {
    tabs: RequestTab[]
    activeId: string
    onActivate: (id: string) => void
    onClose: (id: string) => void
    onNew: () => void
    /** Closes several tabs at once, confirming once for all their unsaved work. */
    onCloseMany: (ids: string[]) => void
    onDuplicate: (id: string) => void
    onCopyCurl: (id: string) => void
    /** Shows a saved tab's file in the collections tree. */
    onReveal: (id: string) => void
    /** Rendered before the tabs, pinned to the left edge. */
    leading?: Snippet
    /** Rendered after the tabs, pinned to the right edge and never scrolled with them. */
    trailing?: Snippet
  }

  let {
    tabs,
    activeId,
    onActivate,
    onClose,
    onNew,
    onCloseMany,
    onDuplicate,
    onCopyCurl,
    onReveal,
    leading,
    trailing
  }: Props = $props()

  /** Shortcut hints must say which modifier this machine actually uses. */
  const mod = navigator.platform.toLowerCase().includes('mac') ? '⌘' : 'Ctrl+'

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

  // The tab a context menu was opened on, and where to draw it.
  let menu = $state<{ id: string; x: number; y: number } | null>(null)

  function openMenu(event: MouseEvent, tab: RequestTab): void {
    event.preventDefault()
    menu = { id: tab.id, x: event.clientX, y: event.clientY }
  }

  /** Shift+F10 or the Menu key: the keyboard's right click, anchored under the tab. */
  function openMenuFromKeyboard(tab: RequestTab): void {
    const rect = document.getElementById(`request-tab-${tab.id}`)?.getBoundingClientRect()
    menu = { id: tab.id, x: rect?.left ?? 0, y: rect?.bottom ?? 0 }
  }

  function menuItems(id: string): MenuItem[] {
    const index = tabs.findIndex((tab) => tab.id === id)
    const tab = tabs[index]
    if (!tab) {
      return []
    }
    const others = tabs.filter((other) => other.id !== id).map((other) => other.id)
    const right = tabs.slice(index + 1).map((other) => other.id)
    return [
      { label: 'Rename', role: 'tab-menu-rename', run: () => startRename(tab) },
      { label: 'Duplicate', role: 'tab-menu-duplicate', run: () => onDuplicate(id) },
      { label: 'Copy as cURL', role: 'tab-menu-curl', run: () => onCopyCurl(id) },
      {
        label: 'Reveal in sidebar',
        role: 'tab-menu-reveal',
        // A scratch or history tab has no file to point at.
        disabled: tab.path === null,
        run: () => onReveal(id)
      },
      { label: 'Close', role: 'tab-menu-close', separated: true, run: () => onClose(id) },
      {
        label: 'Close other tabs',
        role: 'tab-menu-close-others',
        disabled: others.length === 0,
        run: () => onCloseMany(others)
      },
      {
        label: 'Close tabs to the right',
        role: 'tab-menu-close-right',
        disabled: right.length === 0,
        run: () => onCloseMany(right)
      },
      {
        label: 'Close all tabs',
        role: 'tab-menu-close-all',
        run: () => onCloseMany(tabs.map((other) => other.id))
      }
    ]
  }

  function onKeydown(event: KeyboardEvent, index: number): void {
    const { key } = event
    if (key === 'ContextMenu' || (key === 'F10' && event.shiftKey)) {
      event.preventDefault()
      openMenuFromKeyboard(tabs[index])
      return
    }
    if (key === 'F2') {
      event.preventDefault()
      startRename(tabs[index])
      return
    }
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

<!--
  A header, not a bare strip: the app's own controls share this row rather than taking one
  above it, so this is where anything that must not shift the page belongs — an update offer
  arrives here and the layout below it never moves. Only the tabs scroll; the wrapper carries
  the rule and the edges, so a long list of tabs never pushes the environment picker out of reach.
-->
<header class="flex items-stretch border-b border-line">
  {#if leading}
    <div class="flex shrink-0 items-center pl-2">{@render leading()}</div>
  {/if}
  <div
    role="tablist"
    aria-label="Open requests"
    class="scroll-quiet flex min-w-0 flex-1 items-stretch gap-1 overflow-x-auto px-2"
  >
    {#each tabs as tab, index (tab.id)}
      {@const active = tab.id === activeId}
      <div
        role="presentation"
        oncontextmenu={(event) => openMenu(event, tab)}
        class="group flex shrink-0 items-center border-b-2 transition-colors
               {active ? 'border-accent' : 'border-transparent hover:border-line'}"
      >
        {#if renaming === tab.id}
          <span class="flex items-center gap-1.5 py-3 pl-3 text-sm">
            <span class="font-mono text-[10px] uppercase {methodTone(tab.draft.method)}">{tab.draft.method}</span>
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
            class="flex items-center gap-2 py-3.5 pl-3 text-sm transition
                   {active ? 'font-medium text-fg' : 'text-fg-muted hover:text-fg'}"
          >
            <span class="font-mono text-[10px] uppercase {methodTone(tab.draft.method)}">{tab.draft.method}</span>
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
          class="mr-1 rounded p-0.5 text-fg-faint transition hover:bg-line/60 hover:text-fg
                 focus:opacity-100 {active ? '' : 'opacity-0 group-hover:opacity-100'}"
        >
          <svg
            viewBox="0 0 24 24"
            class="h-3.5 w-3.5"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            aria-hidden="true"
          >
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      </div>
    {/each}

    <button
      type="button"
      onclick={onNew}
      aria-label="New request tab"
      title={`New request tab (${mod}T)`}
      class="my-auto shrink-0 rounded-md p-1.5 text-fg-faint transition hover:bg-line/60
             hover:text-fg"
    >
      <svg
        viewBox="0 0 24 24"
        class="h-4 w-4"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        aria-hidden="true"
      >
        <path d="M12 5v14M5 12h14" />
      </svg>
    </button>
  </div>
  {#if trailing}
    <div class="flex shrink-0 items-center gap-2 pr-4">{@render trailing()}</div>
  {/if}
</header>

{#if menu}
  {#key menu}
    <ContextMenu
      items={menuItems(menu.id)}
      x={menu.x}
      y={menu.y}
      label="Tab actions"
      onClose={() => (menu = null)}
    />
  {/key}
{/if}
