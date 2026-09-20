<script lang="ts">
  import type { CaptureOutcome } from '../lib/http'

  interface Props {
    results: CaptureOutcome[]
  }

  let { results }: Props = $props()
</script>

<div data-role="captures" class="border-b border-line px-4 py-2 text-xs">
  <p class="text-fg-muted">Captured for the next request</p>
  <ul class="mt-1 flex flex-wrap gap-x-4 gap-y-0.5">
    {#each results as result, index (index)}
      <li data-role="capture" data-found={result.found} class="flex gap-1.5">
        <span class="shrink-0 {result.found ? 'text-success' : 'text-warning'}" aria-hidden="true">
          {result.found ? '✓' : '✗'}
        </span>
        <span class="sr-only">{result.found ? 'Captured' : 'Missed'}:</span>
        <span class="font-mono text-fg-muted">{result.name}</span>
        {#if !result.found && result.message}
          <span class="min-w-0 truncate text-warning">{result.message}</span>
        {/if}
      </li>
    {/each}
  </ul>
</div>
