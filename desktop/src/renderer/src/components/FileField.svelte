<script lang="ts">
  import { fileLabel, pickFile } from '../lib/files'

  interface Props {
    /** The stored path, or nothing when no file has been chosen. */
    path: string | undefined
    /** The request's collection folder, so a file inside it is stored relative to it. */
    collection: string
    label: string
    onChange: (path: string | undefined) => void
  }

  let { path, collection, label, onChange }: Props = $props()

  let problem = $state('')

  async function choose(): Promise<void> {
    problem = ''
    try {
      const picked = await pickFile(collection)
      if (picked) {
        onChange(picked.stored)
      }
    } catch (cause) {
      problem = cause instanceof Error ? cause.message : String(cause)
    }
  }

  // A path outside the collection is only readable in the session that chose it.
  const outside = $derived(!!path && /^([\\/]|[a-zA-Z]:[\\/])/.test(path))
</script>

<div class="flex min-w-0 flex-1 items-center gap-2">
  <button
    type="button"
    onclick={() => void choose()}
    aria-label="Choose {label}"
    class="shrink-0 rounded-md border border-line px-3 py-1.5 text-sm text-fg-muted transition
           hover:border-accent hover:text-fg"
  >
    {path ? 'Change…' : 'Choose file…'}
  </button>
  {#if path}
    <span
      data-role="chosen-file"
      title={outside ? `${path} (outside the collection; chosen again next session)` : path}
      class="min-w-0 truncate font-mono text-sm text-fg"
    >
      {fileLabel(path)}
    </span>
    {#if outside}
      <span class="shrink-0 text-[10px] uppercase tracking-wide text-fg-faint">this session</span>
    {/if}
    <button
      type="button"
      onclick={() => onChange(undefined)}
      aria-label="Clear {label}"
      class="shrink-0 rounded-md px-1.5 text-fg-faint transition hover:text-fg"
    >
      ×
    </button>
  {:else}
    <span class="text-sm text-fg-faint">No file chosen</span>
  {/if}
  {#if problem}
    <span role="alert" class="min-w-0 truncate text-xs text-danger">{problem}</span>
  {/if}
</div>
