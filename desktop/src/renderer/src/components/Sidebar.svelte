<script lang="ts">
  import type { HistoryEntry } from '../../../shared/history'
  import type { StoreNode } from '../lib/store'
  import HistoryList from './HistoryList.svelte'

  type Panel = 'collections' | 'history'

  interface Props {
    nodes: StoreNode[]
    activePath: string | null
    workspaceRoot: string | null
    history: HistoryEntry[]
    panel?: Panel
    onSelect: (node: StoreNode) => void
    onCreate: (collectionPath: string) => void
    onDelete: (node: StoreNode) => void
    onOpenLocation: (node: StoreNode) => void
    onNewCollection: (name: string) => void
    onOpenFolder: () => void
    onImport: () => void
    onSelectHistory: (entry: HistoryEntry) => void
    onClearHistory: () => void
  }

  let {
    nodes,
    activePath,
    workspaceRoot,
    history,
    panel = $bindable<Panel>('collections'),
    onSelect,
    onCreate,
    onDelete,
    onOpenLocation,
    onNewCollection,
    onOpenFolder,
    onImport,
    onSelectHistory,
    onClearHistory
  }: Props = $props()

  let expanded = $state<string[]>([])
  let confirming = $state<string | null>(null)
  // Folders already seen. Used so a newly discovered folder opens once, while a folder the
  // user deliberately collapsed is not re-opened on the next rescan.
  const seen = new Set<string>()
  let naming = $state(false)
  let name = $state('My Collection')
  let collectionInput = $state<HTMLInputElement>()

  function startNaming(): void {
    naming = true
    name = 'My Collection'
  }

  function submitCollection(event: SubmitEvent): void {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      return
    }
    naming = false
    onNewCollection(trimmed)
  }

  $effect(() => {
    if (naming) {
      queueMicrotask(() => collectionInput?.focus())
    }
  })

  // Open folders the first time they appear; leave collapse decisions alone afterwards.
  $effect(() => {
    const folders: string[] = []
    const collect = (list: StoreNode[]): void => {
      for (const node of list) {
        if (node.type !== 'request') {
          folders.push(node.path)
          collect(node.children ?? [])
        }
      }
    }
    collect(nodes)

    const fresh = folders.filter((path) => !seen.has(path))
    if (fresh.length > 0) {
      for (const path of fresh) {
        seen.add(path)
      }
      expanded = [...expanded, ...fresh]
    }
  })

  function toggle(path: string): void {
    expanded = expanded.includes(path)
      ? expanded.filter((existing) => existing !== path)
      : [...expanded, path]
  }

  type Row = { node: StoreNode; depth: number }
  const rows = $derived.by(() => {
    const out: Row[] = []
    const walk = (list: StoreNode[], depth: number): void => {
      for (const node of list) {
        out.push({ node, depth })
        if (node.type !== 'request' && expanded.includes(node.path)) {
          walk(node.children ?? [], depth + 1)
        }
      }
    }
    walk(nodes, 0)
    return out
  })
</script>

<aside
  data-role="sidebar"
  class="flex h-full w-full flex-col border-r border-line bg-panel"
>
  <div class="flex flex-col gap-1 border-b border-line px-3 py-2">
    <div class="flex items-center justify-between">
      <div role="tablist" aria-label="Sidebar panel" class="flex items-center gap-1">
        <button
          type="button"
          role="tab"
          aria-selected={panel === 'collections'}
          onclick={() => (panel = 'collections')}
          class="rounded-md px-2 py-1 text-xs font-medium uppercase tracking-wide transition
                 {panel === 'collections'
            ? 'bg-line text-fg'
            : 'text-fg-muted hover:text-fg'}"
        >
          Collections
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={panel === 'history'}
          onclick={() => (panel = 'history')}
          class="rounded-md px-2 py-1 text-xs font-medium uppercase tracking-wide transition
                 {panel === 'history' ? 'bg-line text-fg' : 'text-fg-muted hover:text-fg'}"
        >
          History
        </button>
      </div>
      <div class="flex items-center gap-1">
        {#if panel === 'collections'}
          {#if workspaceRoot}
            <button
              type="button"
              onclick={startNaming}
              aria-label="New collection"
              title="New collection"
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
                <path d="M12 5v14M5 12h14" />
              </svg>
            </button>
          {/if}
          {#if workspaceRoot}
            <button
              type="button"
              onclick={onImport}
              aria-label="Import collection"
              title="Import a Postman or Insomnia export"
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
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
            </button>
          {/if}
          <button
            type="button"
            onclick={onOpenFolder}
            aria-label="Open folder"
            title="Open folder"
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
              <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
            </svg>
          </button>
        {:else if history.length > 0}
          <button
            type="button"
            onclick={onClearHistory}
            class="rounded-md px-2 py-1 text-xs text-fg-muted transition hover:bg-line/60
                   hover:text-fg"
          >
            Clear
          </button>
        {/if}
      </div>
    </div>

    {#if naming && panel === 'collections'}
      <form class="mt-1 flex items-center gap-1" onsubmit={submitCollection}>
        <input
          bind:this={collectionInput}
          bind:value={name}
          aria-label="Collection name"
          class="min-w-0 flex-1 rounded-md border border-line bg-base px-2 py-1 text-sm
                 outline-none transition focus:border-accent"
        />
        <button type="submit" class="rounded-md px-2 py-1 text-xs text-accent">Create</button>
      </form>
    {/if}
  </div>

  {#if panel === 'history'}
    <HistoryList entries={history} onSelect={onSelectHistory} />
  {:else}
    <div class="flex-1 overflow-auto py-1">
      {#if rows.length === 0}
        <div class="flex flex-col items-start gap-2 px-3 py-6">
          {#if workspaceRoot}
            <p class="text-sm text-fg-faint">This folder has no collections yet.</p>
            <button
              type="button"
              onclick={startNaming}
              class="rounded-md border border-line px-3 py-1.5 text-sm text-fg-muted transition
                     hover:border-accent hover:text-fg"
            >
              New collection
            </button>
          {:else}
            <p class="text-sm text-fg-faint">No folder open.</p>
            <button
              type="button"
              onclick={onOpenFolder}
              class="rounded-md border border-line px-3 py-1.5 text-sm text-fg-muted transition
                     hover:border-accent hover:text-fg"
            >
              Open a folder
            </button>
          {/if}
        </div>
      {/if}

      {#each rows as row (row.node.path)}
        {@const node = row.node}
        <div
          data-path={node.path}
          data-node-type={node.type}
          class="group flex items-center"
          style="padding-left: {row.depth * 12 + 6}px"
        >
          {#if node.type === 'request'}
            <button
              type="button"
              onclick={() => onSelect(node)}
              class="flex min-w-0 flex-1 items-center gap-2 rounded px-2 py-1 text-left text-sm
                     transition
                     {activePath === node.path
                ? 'bg-line text-fg'
                : 'text-fg-muted hover:bg-line/50 hover:text-fg'}"
            >
              <span class="w-9 shrink-0 font-mono text-[10px] uppercase text-fg-faint">
                {node.method ?? ''}
              </span>
              <span class="truncate">{node.name}</span>
            </button>
            {#if confirming === node.path}
              <button
                type="button"
                onclick={() => {
                  confirming = null
                  onDelete(node)
                }}
                class="mr-1 rounded px-1.5 text-xs font-medium text-danger transition
                       hover:bg-line/60"
              >
                Delete
              </button>
              <button
                type="button"
                onclick={() => (confirming = null)}
                class="mr-1 rounded px-1.5 text-xs text-fg-muted transition hover:bg-line/60"
              >
                Cancel
              </button>
            {:else}
              <button
                type="button"
                onclick={() => (confirming = node.path)}
                aria-label="Delete {node.name}"
                class="mr-1 rounded px-1.5 text-fg-faint opacity-0 transition
                       group-hover:opacity-100 hover:text-danger"
              >
                ×
              </button>
            {/if}
          {:else}
            <button
              type="button"
              onclick={() => toggle(node.path)}
              class="flex min-w-0 flex-1 items-center gap-2 rounded px-2 py-1 text-left text-sm
                     text-fg transition hover:bg-line/50"
            >
              <span class="w-3 shrink-0 text-fg-faint">
                {expanded.includes(node.path) ? '▾' : '▸'}
              </span>
              <span class="truncate">{node.name}</span>
            </button>
            {#if confirming === node.path}
              <button
                type="button"
                onclick={() => {
                  confirming = null
                  onDelete(node)
                }}
                class="mr-1 rounded px-1.5 text-xs font-medium text-danger transition
                       hover:bg-line/60"
              >
                Delete
              </button>
              <button
                type="button"
                onclick={() => (confirming = null)}
                class="mr-1 rounded px-1.5 text-xs text-fg-muted transition hover:bg-line/60"
              >
                Cancel
              </button>
            {:else}
              <button
                type="button"
                onclick={() => onOpenLocation(node)}
                aria-label="Open {node.name} in the file manager"
                class="rounded px-1.5 text-fg-faint opacity-0 transition group-hover:opacity-100
                       hover:text-accent"
              >
                <svg
                  viewBox="0 0 24 24"
                  class="h-3.5 w-3.5"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                </svg>
              </button>
              <button
                type="button"
                onclick={() => onCreate(node.path)}
                aria-label="New request in {node.name}"
                class="rounded px-1.5 text-fg-faint opacity-0 transition group-hover:opacity-100
                       hover:text-accent"
              >
                +
              </button>
              <button
                type="button"
                onclick={() => (confirming = node.path)}
                aria-label="Delete {node.name}"
                class="mr-1 rounded px-1.5 text-fg-faint opacity-0 transition
                       group-hover:opacity-100 hover:text-danger"
              >
                ×
              </button>
            {/if}
          {/if}
        </div>
      {/each}
    </div>
  {/if}
</aside>
