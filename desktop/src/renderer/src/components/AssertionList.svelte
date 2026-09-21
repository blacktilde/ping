<script lang="ts">
  /**
   * The rows of an assertion outcome, shared by the response pane and the run panel so the two
   * read the same: the same tick, the same `type target op expected` phrasing, the same message.
   *
   * It is the list only. The surrounding chrome differs — the response pane heads it with a
   * count, the run panel folds it under a request — so each caller keeps its own.
   */
  import type { AssertionResult } from '../lib/http'

  interface Props {
    results: AssertionResult[]
    /** Sizing for the list itself, since only the caller knows how much room it has. */
    class?: string
  }

  let { results, class: className = '' }: Props = $props()

  function describe(result: AssertionResult): string {
    const subject = result.target ? `${result.type} ${result.target}` : result.type
    return result.expected ? `${subject} ${result.op} ${result.expected}` : `${subject} ${result.op}`
  }
</script>

<ul class="space-y-0.5 {className}">
  {#each results as result, index (index)}
    <li data-role="assertion" data-passed={result.passed} class="flex gap-2 text-xs">
      <span class="w-3 shrink-0 {result.passed ? 'text-success' : 'text-danger'}" aria-hidden="true">
        {result.passed ? '✓' : '✗'}
      </span>
      <span class="sr-only">{result.passed ? 'Passed' : 'Failed'}:</span>
      <span class="min-w-0 truncate font-mono text-fg-muted">{describe(result)}</span>
      {#if !result.passed && result.message}
        <span class="min-w-0 truncate text-danger">{result.message}</span>
      {/if}
    </li>
  {/each}
</ul>
