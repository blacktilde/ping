<script lang="ts">
  import { completeVariables } from '../lib/completion.svelte'
  import type { RequestBody } from '../lib/http'
  import { BODY_MODES } from '../lib/request'
  import { prettyJson } from '../lib/response'
  import CodeEditor from './CodeEditor.svelte'
  import FileField from './FileField.svelte'
  import KeyValueEditor from './KeyValueEditor.svelte'
  import MultipartEditor from './MultipartEditor.svelte'

  interface Props {
    body: RequestBody
    /** The request's collection folder; a file inside it is stored relative to it. */
    collection: string
  }

  let { body, collection }: Props = $props()

  let beautifyError = $state('')

  function beautify(): void {
    const formatted = prettyJson(body.content)
    if (formatted === null) {
      beautifyError = 'Not valid JSON, so it was left as is.'
      return
    }
    beautifyError = ''
    body.content = formatted
  }

  // A message about one body must not outlive an edit or a mode switch.
  $effect(() => {
    void body.content
    void body.type
    beautifyError = ''
  })
</script>

<div class="flex h-full flex-col">
  <div class="flex items-center gap-3 border-b border-line px-5 py-2">
    <select
      bind:value={body.type}
      aria-label="Body mode"
      class="rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none
             transition focus:border-accent focus:ring-3 focus:ring-accent/15"
    >
      {#each BODY_MODES as mode (mode.value)}
        <option value={mode.value}>{mode.label}</option>
      {/each}
    </select>

    {#if body.type === 'raw' || body.type === 'file'}
      <input
        bind:value={body.contentType}
        use:completeVariables
        aria-label="Content type"
        placeholder={body.type === 'file' ? 'application/octet-stream' : 'text/plain'}
        class="min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 font-mono
               text-sm outline-none transition focus:border-accent focus:ring-3 focus:ring-accent/15"
      />
    {/if}

    {#if body.type === 'json'}
      <button
        type="button"
        onclick={beautify}
        disabled={!body.content.trim()}
        title="Re-indent the JSON body"
        class="ml-auto rounded-md border border-line bg-base px-3 py-1.5 text-sm transition
               hover:border-accent disabled:cursor-default disabled:opacity-50
               disabled:hover:border-line"
      >
        Beautify
      </button>
      {#if beautifyError}
        <span role="alert" class="text-xs text-fg-faint">{beautifyError}</span>
      {/if}
    {/if}
  </div>

  <div class="min-h-0 flex-auto">
    {#if body.type === 'none'}
      <p class="px-5 py-6 text-sm text-fg-faint">This request sends no body.</p>
    {:else if body.type === 'json'}
      <CodeEditor bind:value={body.content} language="json" label="JSON request body" pad="px-5" variables />
    {:else if body.type === 'raw'}
      <CodeEditor bind:value={body.content} language="plain" label="Raw request body" pad="px-5" variables />
    {:else if body.type === 'file'}
      <div class="flex items-center px-5 py-4">
        <FileField
          path={body.file || undefined}
          {collection}
          label="body file"
          onChange={(path) => (body.file = path)}
        />
      </div>
      <p class="px-5 text-xs text-fg-faint">
        The file is sent exactly as it is on disk, streamed rather than loaded into memory.
      </p>
    {:else if body.type === 'multipart'}
      <MultipartEditor items={body.fields} {collection} />
    {:else}
      <KeyValueEditor
        items={body.fields}
        nameLabel="Field name"
        valueLabel="Field value"
        addLabel="Add field"
        emptyText="No fields yet."
        variables
      />
    {/if}
  </div>
</div>
