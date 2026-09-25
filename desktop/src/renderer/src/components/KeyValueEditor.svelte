<script lang="ts">
  import { completeVariables } from '../lib/completion.svelte'
  import type { Param } from '../lib/http'
  import { emptyParam } from '../lib/request'

  interface Props {
    items: Param[]
    nameLabel?: string
    valueLabel?: string
    addLabel?: string
    emptyText?: string
    /** Offer `{{name}}` completion: the rows are part of a request, not variable definitions. */
    variables?: boolean
  }

  let {
    items,
    nameLabel = 'Name',
    valueLabel = 'Value',
    addLabel = 'Add row',
    emptyText = 'Nothing here yet.',
    variables = false
  }: Props = $props()

  const noop = { destroy() {} }

  function complete(input: HTMLInputElement) {
    return variables ? completeVariables(input) : noop
  }

  const fieldClass =
    'min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none ' +
    'transition focus:border-accent focus:ring-3 focus:ring-accent/15 disabled:opacity-40'
</script>

<div class="h-full overflow-auto">
  {#if items.length === 0}
    <p class="px-5 pt-6 text-sm text-fg-faint">{emptyText}</p>
  {/if}

  {#each items as item, index (item)}
    <div class="flex items-center gap-2 border-b border-line/60 px-5 py-2">
      <input
        type="checkbox"
        bind:checked={item.enabled}
        aria-label="Enable row"
        class="accent-accent"
      />
      <input
        bind:value={item.name}
        use:complete
        aria-label={nameLabel}
        placeholder="Name"
        class={fieldClass}
      />
      <input
        bind:value={item.value}
        use:complete
        aria-label={valueLabel}
        placeholder="Value"
        class={fieldClass}
      />
      <button
        type="button"
        onclick={() => items.splice(index, 1)}
        aria-label="Remove row"
        class="rounded-md p-1.5 text-fg-faint transition hover:bg-line/60
               hover:text-fg"
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

  <!-- Straight after the last row, not pinned to the bottom of a mostly empty pane. -->
  <div class="px-5 py-3">
    <button
      type="button"
      onclick={() => items.push(emptyParam())}
      class="text-sm font-medium text-accent transition hover:brightness-125"
    >
      + {addLabel}
    </button>
  </div>
</div>
