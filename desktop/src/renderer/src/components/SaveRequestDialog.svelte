<script lang="ts">
  import { untrack } from 'svelte'
  import type { StoreNode } from '../lib/store'

  interface Props {
    /** Collections and folders the request can be saved into, in sidebar order. */
    nodes: StoreNode[]
    /** The draft's current name, offered as the file's name. */
    name: string
    /** Where the last save went, preselected so a run of saves lands in one place. */
    preferred?: string | null
    onSave: (target: string, name: string) => void
    onCancel: () => void
  }

  let { nodes, name, preferred = null, onSave, onCancel }: Props = $props()

  interface Target {
    path: string
    label: string
    depth: number
  }

  /**
   * The tree as a flat list of places a request can live. A request is a leaf, so only
   * collections and folders are offered, and the depth is what indents the option.
   */
  function targetsOf(list: StoreNode[], depth = 0): Target[] {
    const found: Target[] = []
    for (const node of list) {
      if (node.type === 'request') {
        continue
      }
      found.push({ path: node.path, label: node.name, depth })
      found.push(...targetsOf(node.children ?? [], depth + 1))
    }
    return found
  }

  const targets = $derived(targetsOf(nodes))

  // A select shows one line at a time, so nesting has to be spelled out in the option itself.
  const INDENT = '    '

  // The dialog is mounted for one save and thrown away, so both fields are seeded once from
  // the draft as it stands. The preferred target only wins if it is still there; a folder
  // deleted since the last save falls back to the first collection.
  let target = $state(
    untrack(() => {
      const list = targetsOf(nodes)
      return list.some((entry) => entry.path === preferred)
        ? (preferred as string)
        : (list[0]?.path ?? '')
    })
  )
  let requestName = $state(untrack(() => name.trim() || 'Untitled request'))
  let nameInput = $state<HTMLInputElement>()

  // The collection is the question, but the name is what most saves change, so focus lands there.
  $effect(() => {
    queueMicrotask(() => nameInput?.select())
  })

  const valid = $derived(Boolean(target) && requestName.trim().length > 0)

  function submit(event: SubmitEvent): void {
    event.preventDefault()
    if (valid) {
      onSave(target, requestName.trim())
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
    data-role="save-request-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="Save request"
    tabindex="-1"
    class="motion-rise relative z-10 w-full max-w-md rounded-xl border border-line bg-panel shadow-2xl"
  >
    <form onsubmit={submit}>
      <header class="border-b border-line px-5 py-4">
        <h2 class="text-sm font-medium text-fg">Save request</h2>
        <p class="mt-1 text-xs leading-relaxed text-fg-faint">
          This request is not in a collection yet. Choose where it should live.
        </p>
      </header>

      <div class="space-y-4 px-5 py-4 text-sm">
        <div>
          <label for="save-request-target" class="block text-xs text-fg-muted">Collection</label>
          <select
            id="save-request-target"
            data-role="save-request-target"
            bind:value={target}
            class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 outline-none
                   transition focus:border-accent focus:ring-3 focus:ring-accent/15"
          >
            {#each targets as entry (entry.path)}
              <option value={entry.path}>{INDENT.repeat(entry.depth)}{entry.label}</option>
            {/each}
          </select>
        </div>

        <div>
          <label for="save-request-name" class="block text-xs text-fg-muted">Name</label>
          <input
            id="save-request-name"
            data-role="save-request-name"
            bind:this={nameInput}
            bind:value={requestName}
            spellcheck="false"
            class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 outline-none
                   transition focus:border-accent focus:ring-3 focus:ring-accent/15"
          />
        </div>
      </div>

      <div class="flex items-center justify-end gap-2 border-t border-line px-5 py-3">
        <button
          type="button"
          data-role="save-request-cancel"
          onclick={onCancel}
          class="rounded-lg border border-line px-4 py-2 text-sm text-fg-muted transition
                 hover:border-fg-muted hover:text-fg"
        >
          Cancel
        </button>
        <button
          type="submit"
          data-role="save-request-accept"
          disabled={!valid}
          class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition
                 hover:brightness-110 disabled:opacity-40 disabled:hover:brightness-100"
        >
          Save
        </button>
      </div>
    </form>
  </div>
</div>
