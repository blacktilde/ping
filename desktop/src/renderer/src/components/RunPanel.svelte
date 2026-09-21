<script lang="ts">
  /**
   * Runs a collection and shows what happened, request by request.
   *
   * The run itself lives in `lib/run.svelte.ts`, so closing this dialog does not abandon one
   * that is still going. The environment is chosen here rather than taken from the header: the
   * collection being run is often not the one the open tab belongs to.
   */
  import { closeRun, run, startRun } from '../lib/run.svelte'
  import { formatDuration, statusTone } from '../lib/format'
  import { varsCatalog, type EnvironmentRef } from '../lib/vars'

  let environments = $state<EnvironmentRef[]>([])

  // The collection's own environments, not the header's: a run names one of these or none.
  $effect(() => {
    const collection = run.collection
    if (!collection) {
      return
    }
    let current = true
    void varsCatalog(collection)
      .then((catalog) => {
        if (!current) {
          return
        }
        environments = catalog.environments
        if (!environments.some((environment) => environment.path === run.environment)) {
          run.environment = ''
        }
      })
      .catch(() => {
        // The picker is a convenience; a run with no environment still works.
        environments = []
      })
    return () => {
      current = false
    }
  })

  const done = $derived(run.requests.length)
  const summary = $derived(run.result)

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      closeRun()
    }
  }

  function outcome(request: { passed: boolean; error?: string }): 'passed' | 'failed' | 'errored' {
    if (request.error) return 'errored'
    return request.passed ? 'passed' : 'failed'
  }

  function describe(assertion: { type: string; target?: string | null; op: string; expected?: string | null }): string {
    const subject = assertion.target ? `${assertion.type} ${assertion.target}` : assertion.type
    return assertion.expected ? `${subject} ${assertion.op} ${assertion.expected}` : `${subject} ${assertion.op}`
  }
</script>

<svelte:window onkeydown={onKeydown} />

<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
  <button
    type="button"
    aria-label="Dismiss"
    class="absolute inset-0 h-full w-full cursor-default"
    onclick={closeRun}
  ></button>
  <div
    data-role="run-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="Run collection"
    tabindex="-1"
    class="motion-rise relative z-10 flex max-h-[80vh] w-full max-w-2xl flex-col rounded-xl
           border border-line bg-panel shadow-2xl"
  >
    <header class="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
      <div class="min-w-0">
        <h2 class="truncate text-sm font-medium text-fg">Run {run.collectionName}</h2>
        <p class="mt-1 text-xs leading-relaxed text-fg-faint">
          Every request in the collection, in sidebar order, each one's assertions with it. A
          request that gets no response is errored and the run carries on.
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <select
          bind:value={run.environment}
          disabled={run.running}
          aria-label="Environment for the run"
          class="rounded-md border border-line bg-panel px-2 py-1 text-xs text-fg-muted
                 outline-none transition hover:text-fg focus:border-accent disabled:opacity-50"
        >
          <option value="">No environment</option>
          {#each environments as environment (environment.path)}
            <option value={environment.path}>{environment.name}</option>
          {/each}
        </select>
        <button
          type="button"
          data-role="run-start"
          disabled={run.running}
          onclick={() => void startRun()}
          class="rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-white transition
                 hover:brightness-110 disabled:opacity-50"
        >
          {run.running ? 'Running…' : summary ? 'Run again' : 'Run'}
        </button>
      </div>
    </header>

    <div class="min-h-0 flex-1 overflow-auto px-5 py-4 text-sm">
      {#if run.error}
        <p data-role="run-error" role="alert" class="text-xs text-danger">{run.error}</p>
      {/if}

      {#if run.running}
        <p data-role="run-progress" class="mb-3 text-xs text-fg-faint">
          {run.total ? `${done}/${run.total} requests` : 'Starting…'}
        </p>
      {/if}

      {#if run.requests.length === 0 && !run.running && !run.error}
        <p class="text-xs text-fg-faint">Nothing has run yet.</p>
      {/if}

      <ul class="space-y-1">
        {#each run.requests as request, index (`${request.path}-${index}`)}
          {@const verdict = outcome(request)}
          <li data-role="run-request" data-outcome={verdict} class="rounded-md border border-line px-3 py-2">
            <div class="flex items-center gap-2 text-xs">
              <span
                class="w-3 shrink-0 {verdict === 'passed' ? 'text-success' : 'text-danger'}"
                aria-hidden="true">{verdict === 'passed' ? '✓' : '✗'}</span
              >
              <span class="sr-only">{verdict}:</span>
              <span class="w-9 shrink-0 font-mono text-[10px] uppercase text-fg-faint">
                {request.method ?? ''}
              </span>
              <span class="min-w-0 flex-1 truncate text-fg">{request.name}</span>
              {#if request.status !== undefined}
                <span class="shrink-0 font-mono {statusTone(request.status)}">{request.status}</span>
              {/if}
              {#if request.durationMs !== undefined}
                <span class="shrink-0 text-fg-faint">{formatDuration(request.durationMs)}</span>
              {/if}
            </div>
            {#if request.error}
              <p class="mt-1 pl-5 text-xs text-danger">{request.error}</p>
            {/if}
            <!-- Only the failures: a run of fifty requests is a list of what went wrong. -->
            {#each (request.assertions ?? []).filter((assertion) => !assertion.passed) as assertion, position (position)}
              <p class="mt-1 flex gap-2 pl-5 text-xs">
                <span class="min-w-0 truncate font-mono text-fg-muted">{describe(assertion)}</span>
                {#if assertion.message}
                  <span class="min-w-0 truncate text-danger">{assertion.message}</span>
                {/if}
              </p>
            {/each}
          </li>
        {/each}
      </ul>
    </div>

    <footer class="flex items-center justify-between gap-4 border-t border-line px-5 py-3">
      {#if summary}
        <p data-role="run-summary" class="text-xs {summary.failed + summary.errored === 0 ? 'text-success' : 'text-danger'}">
          {summary.passed}/{summary.total} passed{summary.failed > 0 ? `, ${summary.failed} failed` : ''}{summary.errored >
          0
            ? `, ${summary.errored} errored`
            : ''} in {formatDuration(summary.durationMs)}{summary.environment
            ? ` · ${summary.environment}`
            : ''}
        </p>
      {:else}
        <span></span>
      {/if}
      <button
        type="button"
        data-role="run-close"
        onclick={closeRun}
        class="rounded-lg border border-line px-4 py-2 text-sm text-fg-muted transition
               hover:border-fg-muted hover:text-fg"
      >
        Close
      </button>
    </footer>
  </div>
</div>
