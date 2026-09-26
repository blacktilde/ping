<script lang="ts">
  import { untrack } from 'svelte'
  import type { StoreNode } from '../lib/store'

  interface Props {
    /** The workspace tree; only its collections are offered. */
    nodes: StoreNode[]
    onExport: (paths: string[]) => void
    onCancel: () => void
  }

  let { nodes, onExport, onCancel }: Props = $props()

  const collections = $derived(nodes.filter((node) => node.type === 'collection'))

  /** Requests anywhere under a node, so each row says what it holds. */
  function countRequests(node: StoreNode): number {
    return (node.children ?? []).reduce(
      (total, child) => total + (child.type === 'request' ? 1 : countRequests(child)),
      0
    )
  }

  // Mounted for one export and thrown away, so the selection is seeded once: everything, since
  // "export my workspace" is the common case and unticking is cheaper than ticking.
  let selected = $state<string[]>(
    untrack(() => nodes.filter((node) => node.type === 'collection').map((node) => node.path))
  )

  const allSelected = $derived(collections.length > 0 && selected.length === collections.length)

  function toggle(path: string): void {
    selected = selected.includes(path) ? selected.filter((entry) => entry !== path) : [...selected, path]
  }

  function toggleAll(): void {
    selected = allSelected ? [] : collections.map((node) => node.path)
  }

  function submit(event: SubmitEvent): void {
    event.preventDefault()
    // Sidebar order, not click order, so the files and the report read like the tree.
    const paths = collections.map((node) => node.path).filter((path) => selected.includes(path))
    if (paths.length > 0) {
      onExport(paths)
    }
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      onCancel()
    }
  }
</script>

<svelte:window onkeydown={onKeydown} />

<div class="fixed inset-0 z-50 flex items-center justify-center motion-backdrop">
  <button
    type="button"
    aria-label="Dismiss"
    class="absolute inset-0 h-full w-full cursor-default"
    onclick={onCancel}
  ></button>
  <div
    data-role="export-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="Export collections"
    tabindex="-1"
    class="motion-rise relative z-10 w-full max-w-md rounded-xl border border-line bg-panel shadow-2xl"
  >
    <form onsubmit={submit}>
      <header class="border-b border-line px-5 py-4">
        <h2 class="text-sm font-medium text-fg">Export collections</h2>
        <p class="mt-1 text-xs leading-relaxed text-fg-faint">
          Each collection is saved as its own Postman v2.1 file. Secrets are written as
          <code>{'{{name}}'}</code> references, never their values.
        </p>
      </header>

      <div class="px-5 py-3 text-sm">
        {#if collections.length === 0}
          <p class="py-2 text-xs text-fg-muted">There are no collections in this folder yet.</p>
        {:else}
          <label class="flex items-center gap-2 border-b border-line pb-2 text-xs text-fg-muted">
            <input
              type="checkbox"
              data-role="export-select-all"
              checked={allSelected}
              indeterminate={selected.length > 0 && !allSelected}
              onchange={toggleAll}
              class="accent-accent"
            />
            Select all
          </label>
          <ul class="max-h-72 space-y-0.5 overflow-auto py-2">
            {#each collections as node (node.path)}
              {@const requests = countRequests(node)}
              <li>
                <label
                  class="flex items-center gap-2 rounded-md px-1 py-1 transition hover:bg-line/40"
                >
                  <input
                    type="checkbox"
                    data-role="export-collection"
                    data-path={node.path}
                    checked={selected.includes(node.path)}
                    onchange={() => toggle(node.path)}
                    class="accent-accent"
                  />
                  <span class="min-w-0 flex-1 truncate text-fg">{node.name}</span>
                  <span class="text-xs text-fg-faint">
                    {requests} {requests === 1 ? 'request' : 'requests'}
                  </span>
                </label>
              </li>
            {/each}
          </ul>
        {/if}
      </div>

      <div class="flex items-center justify-end gap-2 border-t border-line px-5 py-3">
        <button
          type="button"
          data-role="export-cancel"
          onclick={onCancel}
          class="rounded-lg border border-line px-4 py-2 text-sm text-fg-muted transition
                 hover:border-fg-muted hover:text-fg"
        >
          Cancel
        </button>
        <button
          type="submit"
          data-role="export-accept"
          disabled={selected.length === 0}
          class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition
                 hover:brightness-110 disabled:opacity-40 disabled:hover:brightness-100"
        >
          Export {selected.length === 0 ? '' : selected.length}
          {selected.length === 1 ? 'collection' : 'collections'}
        </button>
      </div>
    </form>
  </div>
</div>
