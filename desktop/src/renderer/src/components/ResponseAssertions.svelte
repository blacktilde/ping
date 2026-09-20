<script lang="ts">
  import type { AssertionResult } from '../lib/http'

  interface Props {
    results: AssertionResult[]
  }

  let { results }: Props = $props()

  const passed = $derived(results.filter((result) => result.passed).length)
  const allPassed = $derived(passed === results.length)

  function describe(result: AssertionResult): string {
    const subject = result.target ? `${result.type} ${result.target}` : result.type
    const object = result.expected ? ` ${result.op} ${result.expected}` : ` ${result.op}`
    return subject + object
  }
</script>

<div data-role="assertions" data-passed={allPassed} class="border-b border-line px-4 py-2 text-xs">
  <p
    data-role="assertions-summary"
    class="font-medium {allPassed ? 'text-success' : 'text-danger'}"
  >
    {passed}/{results.length} assertions passed
  </p>
  <ul class="mt-1 max-h-24 space-y-0.5 overflow-auto">
    {#each results as result, index (index)}
      <li data-role="assertion" data-passed={result.passed} class="flex gap-2">
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
</div>
