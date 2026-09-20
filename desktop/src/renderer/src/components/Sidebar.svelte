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
    onRename: (node: StoreNode, name: string) => void
    onDuplicate: (node: StoreNode) => void
    onMove: (node: StoreNode, target: StoreNode) => void
    onCreateFolder: (parentPath: string, name: string) => void
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
    onRename,
    onDuplicate,
    onMove,
    onCreateFolder,
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

  // --- filter --------------------------------------------------------------------------

  let filter = $state('')
  const filtering = $derived(filter.trim().length > 0)

  /**
   * The tree reduced to what matches: a request whose name or method matches, every ancestor of
   * one, and the whole contents of a folder whose own name matches.
   */
  const visible = $derived.by((): StoreNode[] => {
    const query = filter.trim().toLowerCase()
    if (!query) {
      return nodes
    }
    const keep = (list: StoreNode[], insideMatch: boolean): StoreNode[] => {
      const out: StoreNode[] = []
      for (const node of list) {
        const self = `${node.name} ${node.method ?? ''}`.toLowerCase().includes(query)
        if (node.type === 'request') {
          if (insideMatch || self) out.push(node)
          continue
        }
        const children = keep(node.children ?? [], insideMatch || self)
        if (self || insideMatch || children.length > 0) {
          out.push({ ...node, children })
        }
      }
      return out
    }
    return keep(nodes, false)
  })

  // --- rename and new folder --------------------------------------------------------------

  let renaming = $state<string | null>(null)
  let renameValue = $state('')
  let creatingIn = $state<string | null>(null)
  let folderValue = $state('')

  function startRename(node: StoreNode): void {
    creatingIn = null
    renaming = node.path
    renameValue = node.name
  }

  function commitRename(node: StoreNode): void {
    const value = renameValue.trim()
    renaming = null
    if (value && value !== node.name) {
      onRename(node, value)
    }
  }

  function startFolder(node: StoreNode): void {
    renaming = null
    if (!expanded.includes(node.path)) {
      expanded = [...expanded, node.path]
    }
    creatingIn = node.path
    folderValue = ''
  }

  function commitFolder(parentPath: string): void {
    const value = folderValue.trim()
    creatingIn = null
    if (value) {
      onCreateFolder(parentPath, value)
    }
  }

  /** Focuses a freshly shown input and selects its text, so typing replaces the old name. */
  function focusSelect(element: HTMLInputElement): void {
    element.focus()
    element.select()
  }

  // --- drag and drop ----------------------------------------------------------------------

  // The dragged node lives in component state rather than only in `dataTransfer`, which a
  // synthetic drag (the smoke test) does not always carry.
  let dragging = $state<StoreNode | null>(null)
  let dropTarget = $state<string | null>(null)

  function parentOf(path: string): string {
    const slash = path.lastIndexOf('/')
    return slash < 0 ? '' : path.slice(0, slash)
  }

  /** Obvious mistakes are ignored here; the core refuses the same moves regardless. */
  function canDrop(source: StoreNode | null, target: StoreNode): boolean {
    return (
      source !== null &&
      source.type !== 'collection' &&
      target.type !== 'request' &&
      target.path !== source.path &&
      !target.path.startsWith(`${source.path}/`) &&
      parentOf(source.path) !== target.path
    )
  }

  function dragStart(event: DragEvent, node: StoreNode): void {
    dragging = node
    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', node.path)
      event.dataTransfer.effectAllowed = 'move'
    }
  }

  function dragOver(event: DragEvent, node: StoreNode): void {
    if (canDrop(dragging, node)) {
      event.preventDefault()
      dropTarget = node.path
    }
  }

  function drop(event: DragEvent, node: StoreNode): void {
    const source = dragging
    dragging = null
    dropTarget = null
    if (source && canDrop(source, node)) {
      event.preventDefault()
      onMove(source, node)
    }
  }

  // --- rows ------------------------------------------------------------------------------

  type Row = { node: StoreNode; depth: number }
  const rows = $derived.by(() => {
    const out: Row[] = []
    const walk = (list: StoreNode[], depth: number): void => {
      for (const node of list) {
        out.push({ node, depth })
        // While filtering, every ancestor of a match is open so the match is visible.
        if (node.type !== 'request' && (filtering || expanded.includes(node.path))) {
          walk(node.children ?? [], depth + 1)
        }
      }
    }
    walk(visible, 0)
    return out
  })
</script>

{#snippet action(label: string, run: () => void, path: string)}
  <button
    type="button"
    onclick={run}
    aria-label={label}
    title={label}
    class="rounded px-1 text-fg-faint opacity-0 transition group-hover:opacity-100
           hover:text-accent focus:opacity-100"
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
      <path d={path} />
    </svg>
  </button>
{/snippet}

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
              title="Import a Postman, Insomnia or OpenAPI file"
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
    {#if workspaceRoot && nodes.length > 0}
      <div class="border-b border-line px-2 py-1.5">
        <input
          type="search"
          bind:value={filter}
          aria-label="Filter requests"
          placeholder="Filter requests"
          class="w-full rounded-md border border-line bg-base px-2 py-1 text-xs outline-none
                 transition focus:border-accent"
        />
      </div>
    {/if}
    <div class="flex-1 overflow-auto py-1">
      {#if filtering && rows.length === 0}
        <p data-role="filter-empty" class="px-3 py-4 text-sm text-fg-faint">No matches.</p>
      {/if}
      {#if !filtering && rows.length === 0}
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
          data-drop-target={dropTarget === node.path}
          draggable={node.type !== 'collection' && renaming !== node.path}
          role="treeitem"
          aria-selected={activePath === node.path}
          tabindex="-1"
          ondragstart={(event) => dragStart(event, node)}
          ondragend={() => {
            dragging = null
            dropTarget = null
          }}
          ondragover={(event) => dragOver(event, node)}
          ondragleave={() => {
            if (dropTarget === node.path) dropTarget = null
          }}
          ondrop={(event) => drop(event, node)}
          class="group flex items-center {dropTarget === node.path ? 'bg-accent/15 ring-1 ring-accent' : ''}"
          style="padding-left: {row.depth * 12 + 6}px"
        >
          {#if renaming === node.path}
            <input
              use:focusSelect
              bind:value={renameValue}
              aria-label="Rename {node.name}"
              onkeydown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  commitRename(node)
                } else if (event.key === 'Escape') {
                  event.preventDefault()
                  renaming = null
                }
              }}
              onblur={() => (renaming = null)}
              class="mx-2 my-0.5 min-w-0 flex-1 rounded border border-accent bg-base px-2 py-0.5
                     text-sm outline-none"
            />
          {:else if node.type === 'request'}
            <button
              type="button"
              onclick={() => onSelect(node)}
              ondblclick={() => startRename(node)}
              onkeydown={(event) => {
                if (event.key === 'F2') startRename(node)
              }}
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
          {:else}
            <button
              type="button"
              onclick={() => toggle(node.path)}
              ondblclick={() => startRename(node)}
              onkeydown={(event) => {
                if (event.key === 'F2') startRename(node)
              }}
              class="flex min-w-0 flex-1 items-center gap-2 rounded px-2 py-1 text-left text-sm
                     text-fg transition hover:bg-line/50"
            >
              <span class="w-3 shrink-0 text-fg-faint">
                {filtering || expanded.includes(node.path) ? '▾' : '▸'}
              </span>
              <span class="truncate">{node.name}</span>
            </button>
          {/if}

          {#if renaming !== node.path}
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
              {#if node.type !== 'request'}
                {@render action(`Open ${node.name} in the file manager`, () => onOpenLocation(node), 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z')}
                {@render action(`New request in ${node.name}`, () => onCreate(node.path), 'M12 5v14M5 12h14')}
                {@render action(`New folder in ${node.name}`, () => startFolder(node), 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM12 10v6M9 13h6')}
              {/if}
              {@render action(`Rename ${node.name}`, () => startRename(node), 'M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z')}
              {@render action(`Duplicate ${node.name}`, () => onDuplicate(node), 'M9 9h11v11H9zM5 15V5a1 1 0 0 1 1-1h10')}
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

        {#if creatingIn === node.path}
          <div style="padding-left: {(row.depth + 1) * 12 + 6}px" class="flex items-center">
            <input
              use:focusSelect
              bind:value={folderValue}
              aria-label="New folder name"
              placeholder="Folder name"
              onkeydown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  commitFolder(node.path)
                } else if (event.key === 'Escape') {
                  event.preventDefault()
                  creatingIn = null
                }
              }}
              onblur={() => (creatingIn = null)}
              class="mx-2 my-0.5 min-w-0 flex-1 rounded border border-accent bg-base px-2 py-0.5
                     text-sm outline-none"
            />
          </div>
        {/if}
      {/each}
    </div>
  {/if}
</aside>
