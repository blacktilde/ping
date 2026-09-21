<script lang="ts">
  import type { AssertionResult } from '../lib/http'
  import AssertionList from './AssertionList.svelte'

  interface Props {
    results: AssertionResult[]
  }

  let { results }: Props = $props()

  const passed = $derived(results.filter((result) => result.passed).length)
  const allPassed = $derived(passed === results.length)
</script>

<div data-role="assertions" data-passed={allPassed} class="border-b border-line px-5 py-2.5 text-xs">
  <p
    data-role="assertions-summary"
    class="font-medium {allPassed ? 'text-success' : 'text-danger'}"
  >
    {passed}/{results.length} assertions passed
  </p>
  <AssertionList {results} class="mt-1 max-h-24 overflow-auto" />
</div>
