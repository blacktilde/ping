<script lang="ts">
  import type { RequestBody } from '../lib/http'
  import { BODY_MODES } from '../lib/request'
  import CodeEditor from './CodeEditor.svelte'
  import KeyValueEditor from './KeyValueEditor.svelte'

  interface Props {
    body: RequestBody
  }

  let { body }: Props = $props()
</script>

<div class="flex h-full flex-col">
  <div class="flex items-center gap-3 border-b border-line px-4 py-2">
    <select
      bind:value={body.type}
      aria-label="Body mode"
      class="rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none
             transition focus:border-accent"
    >
      {#each BODY_MODES as mode (mode.value)}
        <option value={mode.value}>{mode.label}</option>
      {/each}
    </select>

    {#if body.type === 'raw'}
      <input
        bind:value={body.contentType}
        aria-label="Content type"
        placeholder="text/plain"
        class="min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 font-mono
               text-sm outline-none transition focus:border-accent"
      />
    {/if}
  </div>

  <div class="min-h-0 flex-1">
    {#if body.type === 'none'}
      <p class="px-4 py-6 text-sm text-neutral-600">This request sends no body.</p>
    {:else if body.type === 'json'}
      {#key body.type}
        <CodeEditor bind:value={body.content} language="json" label="JSON request body" />
      {/key}
    {:else if body.type === 'raw'}
      {#key body.type}
        <CodeEditor bind:value={body.content} language="plain" label="Raw request body" />
      {/key}
    {:else}
      <KeyValueEditor
        items={body.fields}
        nameLabel="Field name"
        valueLabel="Field value"
        addLabel="Add field"
        emptyText="No fields yet."
      />
    {/if}
  </div>
</div>
