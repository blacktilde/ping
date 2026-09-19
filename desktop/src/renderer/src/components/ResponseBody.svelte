<script lang="ts">
  import type { HttpResponse } from '../lib/http'
  import { isHtml, prettyJson } from '../lib/response'
  import CodeEditor from './CodeEditor.svelte'

  interface Props {
    response: HttpResponse
  }

  type View = 'pretty' | 'raw' | 'preview'

  let { response }: Props = $props()

  let view = $state<View>('pretty')

  const raw = $derived(response.body.content ?? '')
  const pretty = $derived(prettyJson(raw) ?? raw)
  const hasPreview = $derived(isHtml(response.body.contentType))
  const views: View[] = ['pretty', 'raw', 'preview']
</script>

<div class="flex h-full flex-col">
  <div class="flex items-center gap-1 border-b border-line px-3 py-1.5">
    {#each views as option (option)}
      <button
        type="button"
        disabled={option === 'preview' && !hasPreview}
        aria-pressed={view === option}
        onclick={() => (view = option)}
        class="rounded-md px-2.5 py-1 text-xs capitalize transition disabled:cursor-not-allowed
               disabled:opacity-30
               {view === option
          ? 'bg-line text-neutral-100'
          : 'text-neutral-500 hover:text-neutral-300'}"
      >
        {option}
      </button>
    {/each}
    {#if !response.body.textual}
      <span class="ml-auto text-xs text-neutral-600">Binary response</span>
    {/if}
  </div>

  {#if response.body.truncated}
    <p class="border-b border-amber-900/50 bg-amber-950/30 px-4 py-2 text-xs text-amber-300">
      Response is larger than the display cap; only the beginning is shown.
    </p>
  {/if}

  <div class="min-h-0 flex-1">
    {#if !response.body.textual}
      <div class="flex h-full items-center justify-center text-sm text-neutral-600">
        Binary response — not displayed
      </div>
    {:else if view === 'preview'}
      {#if hasPreview}
        <iframe
          title="Response preview"
          sandbox=""
          srcdoc={raw}
          class="h-full w-full border-0 bg-white"
        ></iframe>
      {:else}
        <div class="flex h-full items-center justify-center text-sm text-neutral-600">
          Preview is available for HTML responses.
        </div>
      {/if}
    {:else if view === 'pretty'}
      {#key view}
        <CodeEditor value={pretty} language="json" label="Response body, pretty" readonly />
      {/key}
    {:else}
      {#key view}
        <CodeEditor value={raw} language="plain" label="Response body, raw" readonly />
      {/key}
    {/if}
  </div>
</div>
