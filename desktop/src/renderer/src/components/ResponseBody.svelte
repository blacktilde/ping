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
  const parsed = $derived(response.body.textual ? prettyJson(raw) : null)
  const pretty = $derived(parsed ?? raw)
  const hasPreview = $derived(isHtml(response.body.contentType) || hasImageType(response.body.contentType))
  const image = $derived(
    hasImageType(response.body.contentType) && response.body.base64 != null
  )
  const imageSrc = $derived(
    image ? `data:${response.body.contentType};base64,${response.body.base64}` : null
  )
  const looksJson = $derived((response.body.contentType ?? '').toLowerCase().includes('json'))
  // Pretty falls back to raw; say so rather than silently showing unformatted text.
  const invalidJson = $derived(
    looksJson && response.body.textual && raw.trim().length > 0 && parsed === null
  )

  const views: View[] = ['pretty', 'raw', 'preview']

  function enabled(option: View): boolean {
    return option !== 'preview' || hasPreview
  }

  function move(delta: number): void {
    const available = views.filter(enabled)
    const index = available.indexOf(view)
    view = available[((index === -1 ? 0 : index) + delta + available.length) % available.length]
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      event.preventDefault()
      move(1)
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      event.preventDefault()
      move(-1)
    }
  }

  function hasImageType(contentType: string | null | undefined): boolean {
    return (contentType ?? '').toLowerCase().startsWith('image/')
  }

  // Pretty and raw persist across sends; a view that no longer applies (preview on a
  // non-HTML body) falls back rather than showing the previous response's mode.
  $effect(() => {
    if (!enabled(view)) {
      view = 'pretty'
    }
  })
</script>

<div class="flex h-full flex-col">
  <div class="flex items-center gap-1 border-b border-line px-3 py-1.5">
    <div role="radiogroup" aria-label="Response body view" class="flex items-center gap-1">
      {#each views as option (option)}
        <button
          type="button"
          role="radio"
          aria-checked={view === option}
          disabled={!enabled(option)}
          tabindex={view === option ? 0 : -1}
          onclick={() => (view = option)}
          onkeydown={onKeydown}
          class="rounded-md px-2.5 py-1 text-xs capitalize transition disabled:cursor-not-allowed
                 disabled:opacity-30
                 {view === option
            ? 'bg-line text-fg'
            : 'text-fg-muted hover:text-fg'}"
        >
          {option}
        </button>
      {/each}
    </div>

    <span class="ml-auto text-xs text-fg-faint">
      {#if !response.body.textual}
        Binary response
      {:else if invalidJson}
        Not valid JSON; showing the raw text
      {/if}
    </span>
  </div>

  {#if response.body.truncated}
    <p class="border-b border-warning-soft bg-warning-soft px-4 py-2 text-xs text-warning">
      Response is larger than the display cap; only the beginning is shown.
    </p>
  {/if}

  <div class="min-h-0 flex-1">
    {#if !response.body.textual && imageSrc}
      <div class="flex h-full items-center justify-center overflow-auto p-4">
        <img
          src={imageSrc}
          alt="Response preview"
          class="max-h-full max-w-full rounded-md border border-line"
        />
      </div>
    {:else if !response.body.textual}
      <div class="flex h-full flex-col items-center justify-center gap-3 text-sm text-fg-faint">
        <p>
          Binary response{response.body.contentType
            ? ` (${response.body.contentType})`
            : ''} — not displayed.
        </p>
        <p class="text-xs">Use Save in the response header to keep it.</p>
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
        <div class="flex h-full items-center justify-center text-sm text-fg-faint">
          Preview is available for HTML responses.
        </div>
      {/if}
    {:else if view === 'pretty'}
      <CodeEditor value={pretty} language="json" label="Response body, pretty" readonly />
    {:else}
      <CodeEditor value={raw} language="plain" label="Response body, raw" readonly />
    {/if}
  </div>
</div>
