<script lang="ts">
  import type { Param } from '../lib/http'
  import { emptyParam } from '../lib/request'

  interface Props {
    items: Param[]
    nameLabel?: string
    valueLabel?: string
    addLabel?: string
    emptyText?: string
  }

  let {
    items,
    nameLabel = 'Name',
    valueLabel = 'Value',
    addLabel = 'Add row',
    emptyText = 'Nothing here yet.'
  }: Props = $props()

  const fieldClass =
    'min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none ' +
    'transition focus:border-accent disabled:opacity-40'
</script>

<div class="flex h-full flex-col">
  <div class="flex-1 overflow-auto">
    {#if items.length === 0}
      <p class="px-4 py-6 text-sm text-fg-faint">{emptyText}</p>
    {/if}

    {#each items as item, index (item)}
      <div class="flex items-center gap-2 border-b border-line/60 px-4 py-2">
        <input
          type="checkbox"
          bind:checked={item.enabled}
          aria-label="Enable row"
          class="accent-accent"
        />
        <input
          bind:value={item.name}
          aria-label={nameLabel}
          placeholder="Name"
          class={fieldClass}
        />
        <input
          bind:value={item.value}
          aria-label={valueLabel}
          placeholder="Value"
          class={fieldClass}
        />
        <button
          type="button"
          onclick={() => items.splice(index, 1)}
          aria-label="Remove row"
          class="rounded-md px-2 py-1 text-lg leading-none text-fg-faint transition
                 hover:text-fg"
        >
          ×
        </button>
      </div>
    {/each}
  </div>

  <div class="border-t border-line p-2">
    <button
      type="button"
      onclick={() => items.push(emptyParam())}
      class="rounded-md px-3 py-1.5 text-sm text-fg-muted transition hover:text-accent"
    >
      + {addLabel}
    </button>
  </div>
</div>
