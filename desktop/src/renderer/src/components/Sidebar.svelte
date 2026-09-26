<script lang="ts">
  import { tick } from 'svelte'
  import type { HistoryEntry } from '../../../shared/history'
  import { methodTone } from '../lib/format'
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
    onRun: (node: StoreNode) => void
    /** Saves the collection as a Postman v2.1 file. */
    onExport: (node: StoreNode) => void
    onDelete: (node: StoreNode) => void
    onOpenLocation: (node: StoreNode) => void
    onRename: (node: StoreNode, name: string) => void
    onDuplicate: (node: StoreNode) => void
    onMove: (node: StoreNode, target: StoreNode) => void
    /** Puts a request just before or after another one, moving it into that folder if need be. */
    onPlace: (node: StoreNode, target: StoreNode, where: 'before' | 'after') => void
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
    onRun,
    onExport,
    onDelete,
    onOpenLocation,
    onRename,
    onDuplicate,
    onMove,
    onPlace,
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

  /** Every collection and folder path in the tree, at any depth. */
  function folderPaths(list: StoreNode[]): string[] {
    const folders: string[] = []
    const collect = (level: StoreNode[]): void => {
      for (const node of level) {
        if (node.type !== 'request') {
          folders.push(node.path)
          collect(node.children ?? [])
        }
      }
    }
    collect(list)
    return folders
  }

  // Open folders the first time they appear; leave collapse decisions alone afterwards.
  $effect(() => {
    const folders = folderPaths(nodes)

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

  /**
   * Brings a request into view: the collections panel, every folder above it open, and a filter
   * that would hide it cleared. Focus lands on the row so the keyboard can carry on from there.
   */
  export async function reveal(path: string): Promise<void> {
    panel = 'collections'
    const segments = path.split('/')
    const ancestors = segments
      .slice(0, -1)
      .map((_, index) => segments.slice(0, index + 1).join('/'))
    expanded = [...new Set([...expanded, ...ancestors])]
    if (filtering && !rows.some((row) => row.node.path === path)) {
      filter = ''
    }
    await tick()
    const row = [...document.querySelectorAll<HTMLElement>('[role="treeitem"]')].find(
      (element) => element.dataset.path === path
    )
    row?.scrollIntoView({ block: 'nearest' })
    row?.querySelector<HTMLButtonElement>('button')?.focus()
  }

  function expandAll(): void {
    expanded = folderPaths(nodes)
  }

  function collapseAll(): void {
    expanded = []
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
  // Dropping a request on another request places it above or below that one instead.
  let dropPlace = $state<{ path: string; where: 'before' | 'after' } | null>(null)

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

  function canPlace(source: StoreNode | null, target: StoreNode): boolean {
    return source !== null && source.type === 'request' && target.type === 'request' && source.path !== target.path
  }

  function clearDrag(): void {
    dragging = null
    dropTarget = null
    dropPlace = null
  }

  /** The request next to this one in its folder, looked up in the whole tree, not the filtered one. */
  function neighbour(node: StoreNode, step: -1 | 1): StoreNode | null {
    const parent = parentOf(node.path)
    const find = (list: StoreNode[]): StoreNode[] | null => {
      for (const entry of list) {
        if (entry.path === parent) return entry.children ?? []
        const nested = entry.type === 'request' ? null : find(entry.children ?? [])
        if (nested) return nested
      }
      return null
    }
    const siblings = (find(nodes) ?? []).filter((entry) => entry.type === 'request')
    const index = siblings.findIndex((entry) => entry.path === node.path)
    return index < 0 ? null : (siblings[index + step] ?? null)
  }

  /** Alt+Up and Alt+Down do what dragging does, a step at a time. */
  function shift(event: KeyboardEvent, node: StoreNode): void {
    if (!event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return
    event.preventDefault()
    const up = event.key === 'ArrowUp'
    const target = neighbour(node, up ? -1 : 1)
    if (target) {
      onPlace(node, target, up ? 'before' : 'after')
    }
  }

  function dragStart(event: DragEvent, node: StoreNode): void {
    dragging = node
    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', node.path)
      event.dataTransfer.effectAllowed = 'move'
    }
  }

  function dragOver(event: DragEvent, node: StoreNode): void {
    if (canPlace(dragging, node)) {
      event.preventDefault()
      const row = (event.currentTarget as HTMLElement).getBoundingClientRect()
      dropPlace = { path: node.path, where: event.clientY < row.top + row.height / 2 ? 'before' : 'after' }
      dropTarget = null
    } else if (canDrop(dragging, node)) {
      event.preventDefault()
      dropTarget = node.path
      dropPlace = null
    }
  }

  function drop(event: DragEvent, node: StoreNode): void {
    const source = dragging
    const place = dropPlace
    clearDrag()
    if (source && place?.path === node.path && canPlace(source, node)) {
      event.preventDefault()
      onPlace(source, node, place.where)
    } else if (source && canDrop(source, node)) {
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
    class="rounded px-1 text-fg-faint transition hover:text-accent"
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
  <!-- py-2.5 lands this rule level with the request strip's across the divider. -->
  <div class="flex flex-col gap-1 border-b border-line px-3 py-2.5">
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
                 outline-none transition focus:border-accent focus:ring-3 focus:ring-accent/15"
        />
        <button type="submit" class="rounded-md px-2 py-1 text-xs text-accent">Create</button>
      </form>
    {/if}
  </div>

  {#if panel === 'history'}
    <HistoryList entries={history} onSelect={onSelectHistory} />
  {:else}
    {#if workspaceRoot && nodes.length > 0}
      <div class="flex items-center gap-1 border-b border-line px-2 py-1.5">
        <input
          type="search"
          bind:value={filter}
          aria-label="Filter requests"
          placeholder="Filter requests"
          class="min-w-0 flex-1 rounded-md border border-line bg-base px-2 py-1 text-xs outline-none
                 transition focus:border-accent focus:ring-3 focus:ring-accent/15"
        />
        <!-- While filtering every match's ancestors are forced open, so these would do nothing. -->
        <div class="flex items-center {filtering ? 'pointer-events-none opacity-40' : ''}">
          {@render action('Expand all', expandAll, 'M7 15l5 5 5-5M7 9l5-5 5 5')}
          {@render action('Collapse all', collapseAll, 'M7 20l5-5 5 5M7 4l5 5 5-5')}
        </div>
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
          ondragend={clearDrag}
          ondragover={(event) => dragOver(event, node)}
          ondragleave={() => {
            if (dropTarget === node.path) dropTarget = null
            if (dropPlace?.path === node.path) dropPlace = null
          }}
          ondrop={(event) => drop(event, node)}
          class="group relative flex items-center {dropTarget === node.path ? 'bg-accent/15 ring-1 ring-accent' : ''}"
          style="padding-left: {row.depth * 12 + 6}px; padding-right: 6px"
        >
          {#if dropPlace?.path === node.path}
            <div
              data-role="drop-line"
              data-where={dropPlace.where}
              class="pointer-events-none absolute right-1 z-10 h-0.5 rounded bg-accent
                     {dropPlace.where === 'before' ? '-top-px' : '-bottom-px'}"
              style="left: {row.depth * 12 + 6}px"
            ></div>
          {/if}
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
                shift(event, node)
              }}
              class="flex min-w-0 flex-1 items-center gap-2 rounded px-2 py-1 text-left text-sm
                     transition
                     {activePath === node.path
                ? 'bg-line text-fg'
                : 'text-fg-muted hover:bg-line/50 hover:text-fg'}"
            >
              <span class="w-12 shrink-0 font-mono text-[10px] uppercase {methodTone(node.method ?? '')}">
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
              <svg
                viewBox="0 0 24 24"
                class="h-3 w-3 shrink-0 text-fg-faint transition-transform duration-150
                       {filtering || expanded.includes(node.path) ? 'rotate-90' : ''}"
                fill="none"
                stroke="currentColor"
                stroke-width="2.5"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <polyline points="9 6 15 12 9 18" />
              </svg>
              <span class="truncate">{node.name}</span>
            </button>
          {/if}

          {#if renaming !== node.path}
            <!-- Overlaid rather than in flow: the hidden buttons would otherwise take the
                 room the name needs, clipping it and shortening the selected highlight. -->
            <div
              class="absolute inset-y-0 right-1 flex items-center rounded bg-line pl-1
                     {confirming === node.path
                ? ''
                : 'pointer-events-none opacity-0 group-focus-within:pointer-events-auto group-focus-within:opacity-100 group-hover:pointer-events-auto group-hover:opacity-100'}"
            >
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
              {#if node.type === 'collection'}
                <!-- A run is a whole collection: the core runs the folder that has the
                     collection file, so a sub-folder is not one of them. -->
                {@render action(`Run ${node.name}`, () => onRun(node), 'M6 4l13 8-13 8z')}
                {@render action(`Export ${node.name} for Postman`, () => onExport(node), 'M12 15V3M7 8l5-5 5 5M5 15v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4')}
              {/if}
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
                title="Delete {node.name}"
                class="mr-1 rounded p-1 text-fg-faint transition hover:text-danger"
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
                  <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M10 11v6M14 11v6" />
                </svg>
              </button>
            {/if}
            </div>
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
