<script lang="ts">
  import { secretRows } from '../lib/secrets.svelte'

  const field =
    'min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none ' +
    'transition focus:border-accent focus:ring-3 focus:ring-accent/15'
</script>

<div class="flex flex-col">
  {#if secretRows.length === 0}
    <p class="px-3 py-4 text-sm text-fg-faint">No secrets stored.</p>
  {/if}

  {#each secretRows as row, index (row)}
    <div class="flex items-center gap-2 border-b border-line/60 px-3 py-2">
      <input bind:value={row.name} aria-label="Secret name" placeholder="Name" class={field} />
      <input
        bind:value={row.value}
        aria-label="Secret value"
        type="password"
        autocomplete="off"
        placeholder="Value (write-only)"
        class={field}
      />
      <button
        type="button"
        onclick={() => secretRows.splice(index, 1)}
        aria-label="Remove secret"
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

  <div class="p-2">
    <button
      type="button"
      onclick={() => secretRows.push({ name: '', value: '' })}
      class="rounded-md px-3 py-1.5 text-sm text-fg-muted transition hover:text-accent"
    >
      + Add secret
    </button>
  </div>
</div>
