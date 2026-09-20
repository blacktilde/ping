<script lang="ts">
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
    'transition focus:border-accent disabled:opacity-40'

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
  <div class="flex-1 overflow-auto">
    {#if items.length === 0}
      <p class="px-4 py-6 text-sm text-fg-faint">No fields yet.</p>
    {/if}

    {#each items as item, index (item)}
      <div class="flex items-center gap-2 border-b border-line/60 px-4 py-2">
        <input
          type="checkbox"
          bind:checked={item.enabled}
          aria-label="Enable row"
          class="accent-accent"
        />
        <input bind:value={item.name} aria-label="Field name" placeholder="Name" class={fieldClass} />
        <select
          value={isFileParam(item) ? 'file' : 'text'}
          onchange={(event) => setKind(item, event.currentTarget.value as 'text' | 'file')}
          aria-label="Field type"
          class="rounded-md border border-line bg-base px-2 py-1.5 text-sm outline-none transition
                 focus:border-accent"
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
                   outline-none transition focus:border-accent"
          />
        {:else}
          <input bind:value={item.value} aria-label="Field value" placeholder="Value" class={fieldClass} />
        {/if}
        <button
          type="button"
          onclick={() => items.splice(index, 1)}
          aria-label="Remove row"
          class="rounded-md px-2 py-1 text-lg leading-none text-fg-faint transition hover:text-fg"
        >
          ×
        </button>
      </div>
    {/each}
  </div>

  <div class="flex gap-1 border-t border-line p-2">
    <button
      type="button"
      onclick={() => items.push(emptyParam())}
      class="rounded-md px-3 py-1.5 text-sm text-fg-muted transition hover:text-accent"
    >
      + Add field
    </button>
    <button
      type="button"
      onclick={() => items.push({ ...emptyParam(), file: '' })}
      class="rounded-md px-3 py-1.5 text-sm text-fg-muted transition hover:text-accent"
    >
      + Add file
    </button>
  </div>
</div>
