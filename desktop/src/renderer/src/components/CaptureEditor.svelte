<script lang="ts">
  import type { Capture, CaptureSource } from '../lib/http'
  import { CAPTURE_SOURCES, emptyCapture } from '../lib/request'

  interface Props {
    items: Capture[]
  }

  let { items }: Props = $props()

  const fieldClass =
    'min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none ' +
    'transition focus:border-accent disabled:opacity-40'
  const selectClass =
    'rounded-md border border-line bg-base px-2 py-1.5 text-sm outline-none transition ' +
    'focus:border-accent'

  function targetPlaceholder(source: CaptureSource): string {
    return source === 'header' ? 'Header name' : '$.path.to.value'
  }
</script>

<div class="flex h-full flex-col">
  <div class="min-h-0 flex-auto overflow-auto">
    {#if items.length === 0}
      <p class="px-5 py-6 text-sm text-fg-faint">
        Nothing captured. Keep a value from the response, such as a token, and use it in the next
        request as <code class="font-mono">&#123;&#123;name&#125;&#125;</code>.
      </p>
    {/if}

    {#each items as item, index (item)}
      <div class="flex items-center gap-2 border-b border-line/60 px-5 py-2">
        <input
          type="checkbox"
          checked={item.enabled !== false}
          onchange={(event) => (item.enabled = event.currentTarget.checked)}
          aria-label="Enable capture"
          class="accent-accent"
        />
        <input
          bind:value={item.name}
          aria-label="Capture variable name"
          placeholder="Variable name"
          class={fieldClass}
        />
        <span class="text-xs text-fg-faint">from</span>
        <select
          bind:value={item.source}
          aria-label="Capture source"
          class={selectClass}
        >
          {#each CAPTURE_SOURCES as option (option.value)}
            <option value={option.value}>{option.label}</option>
          {/each}
        </select>
        {#if item.source !== 'status'}
          <input
            bind:value={item.target}
            aria-label="Capture target"
            placeholder={targetPlaceholder(item.source)}
            class={fieldClass}
          />
        {:else}
          <span class="flex-1"></span>
        {/if}
        <button
          type="button"
          onclick={() => items.splice(index, 1)}
          aria-label="Remove capture"
          class="rounded-md px-2 py-1 text-lg leading-none text-fg-faint transition
                 hover:text-fg"
        >
          ×
        </button>
      </div>
    {/each}
  </div>

  <div class="border-t border-line px-5 py-3">
    <button
      type="button"
      onclick={() => items.push(emptyCapture())}
      class="text-sm font-medium text-accent transition hover:brightness-125"
    >
      + Add capture
    </button>
  </div>
</div>
