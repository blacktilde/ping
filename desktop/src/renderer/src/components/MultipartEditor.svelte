<script lang="ts">
  import { completeVariables } from '../lib/completion.svelte'
  import { isFileParam, type Param } from '../lib/http'
  import { emptyParam } from '../lib/request'
  import FileField from './FileField.svelte'

  interface Props {
    items: Param[]
    collection: string
  }

  let { items, collection }: Props = $props()

  const fieldClass =
    'min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none ' +
    'transition focus:border-accent focus:ring-3 focus:ring-accent/15 disabled:opacity-40'

  /** Switching a row's kind drops what the other kind uses, so nothing stale is sent or saved. */
  function setKind(item: Param, kind: 'text' | 'file'): void {
    if (kind === 'file') {
      item.value = ''
      item.file = item.file ?? ''
    } else {
      delete item.file
      delete item.filename
      delete item.contentType
    }
  }

  function setPath(item: Param, path: string | undefined): void {
    if (path === undefined) {
      item.file = ''
      delete item.filename
    } else {
      item.file = path
    }
  }
</script>

<div class="flex h-full flex-col">
  <div class="min-h-0 flex-auto overflow-auto">
    {#if items.length === 0}
      <p class="px-5 py-6 text-sm text-fg-faint">No fields yet.</p>
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
          use:completeVariables
          aria-label="Field name"
          placeholder="Name"
          class={fieldClass}
        />
        <select
          value={isFileParam(item) ? 'file' : 'text'}
          onchange={(event) => setKind(item, event.currentTarget.value as 'text' | 'file')}
          aria-label="Field type"
          class="rounded-md border border-line bg-base px-2 py-1.5 text-sm outline-none transition
                 focus:border-accent focus:ring-3 focus:ring-accent/15"
        >
          <option value="text">Text</option>
          <option value="file">File</option>
        </select>
        {#if isFileParam(item)}
          <FileField
            path={item.file || undefined}
            {collection}
            label="file for {item.name || 'this field'}"
            onChange={(path) => setPath(item, path)}
          />
          <input
            bind:value={item.contentType}
            aria-label="File content type"
            placeholder="auto"
            class="w-32 shrink-0 rounded-md border border-line bg-base px-2 py-1.5 font-mono text-xs
                   outline-none transition focus:border-accent focus:ring-3 focus:ring-accent/15"
          />
        {:else}
          <input
            bind:value={item.value}
            use:completeVariables
            aria-label="Field value"
            placeholder="Value"
            class={fieldClass}
          />
        {/if}
        <button
          type="button"
          onclick={() => items.splice(index, 1)}
          aria-label="Remove row"
          class="rounded-md p-1.5 text-fg-faint transition hover:bg-line/60 hover:text-fg"
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
  </div>

  <div class="flex gap-4 border-t border-line px-5 py-3">
    <button
      type="button"
      onclick={() => items.push(emptyParam())}
      class="text-sm font-medium text-accent transition hover:brightness-125"
    >
      + Add field
    </button>
    <button
      type="button"
      onclick={() => items.push({ ...emptyParam(), file: '' })}
      class="text-sm font-medium text-accent transition hover:brightness-125"
    >
      + Add file
    </button>
  </div>
</div>
