<script lang="ts">
  import type { StoreNode } from '../lib/store'

  interface Props {
    nodes: StoreNode[]
    activePath: string | null
    onSelect: (node: StoreNode) => void
    onCreate: (collectionPath: string) => void
    onOpenFolder: () => void
  }

  let { nodes, activePath, onSelect, onCreate, onOpenFolder }: Props = $props()

  let expanded = $state<string[]>([])

  // Folders are open by default. Newly discovered ones join the set without re-opening
  // anything the user deliberately closed.
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

    const missing = folders.filter((path) => !expanded.includes(path))
    if (missing.length > 0) {
      expanded = [...expanded, ...missing]
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
  class="flex h-full w-64 shrink-0 flex-col border-r border-line bg-panel"
>
  <div class="flex items-center justify-between border-b border-line px-3 py-2">
    <span class="text-xs font-medium uppercase tracking-wide text-fg-muted">Collections</span>
    <button
      type="button"
      onclick={onOpenFolder}
      class="rounded-md px-2 py-1 text-xs text-fg-muted transition hover:bg-line/60 hover:text-fg"
    >
      Open folder
    </button>
  </div>

  <div class="flex-1 overflow-auto py-1">
    {#if rows.length === 0}
      <div class="flex flex-col items-start gap-2 px-3 py-6">
        <p class="text-sm text-fg-faint">No collections yet.</p>
        <button
          type="button"
          onclick={onOpenFolder}
          class="rounded-md border border-line px-3 py-1.5 text-sm text-fg-muted transition
                 hover:border-accent hover:text-fg"
        >
          Open a folder
        </button>
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
          <button
            type="button"
            onclick={() => onCreate(node.path)}
            aria-label="New request in {node.name}"
            class="mr-1 rounded px-1.5 text-fg-faint opacity-0 transition
                   group-hover:opacity-100 hover:text-accent"
          >
            +
          </button>
        {/if}
      </div>
    {/each}
  </div>
</aside>
