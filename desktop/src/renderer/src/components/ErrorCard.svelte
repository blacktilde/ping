<script lang="ts">
  import { copyText } from '../lib/clipboard'
  import { describeError, originOf, stageRows, type Remedy } from '../lib/errors'
  import { formatProbeMs } from '../lib/format'
  import { probeOrigin, type ProbeResult } from '../lib/probe'

  interface Props {
    /** What the core said, unchanged. */
    message: string
    /** The request's url as typed; it may hold variables. */
    url: string
    /** Whether the request verifies TLS; the connection check follows it. */
    verifyTls?: boolean
    onRetry: () => void
    /** Sends the request again once with TLS verification off; the request keeps its own setting. */
    onSkipTls?: () => void
    onOpenNetwork?: () => void
  }

  let { message, url, verifyTls = true, onRetry, onSkipTls, onOpenNetwork }: Props = $props()

  const info = $derived(describeError(message, url))
  const origin = $derived(originOf(url))

  // A check belongs to the failure it was run for.
  let probe = $state<ProbeResult | null>(null)
  let probeFailure = $state('')
  let probing = $state(false)
  let copied = $state(false)
  let copiedTimer: number | undefined

  $effect(() => {
    void message
    probe = null
    probeFailure = ''
  })

  const rows = $derived(stageRows(info, url, probe))
  const sendKey = navigator.platform.toLowerCase().includes('mac') ? '⌘↵' : 'Ctrl+↵'

  async function check(): Promise<void> {
    if (!origin) return
    probing = true
    probeFailure = ''
    try {
      probe = await probeOrigin(origin, verifyTls)
    } catch (cause) {
      probe = null
      probeFailure = cause instanceof Error ? cause.message : String(cause)
    } finally {
      probing = false
    }
  }

  async function copy(): Promise<void> {
    // The origin, never the path or query: either can hold a credential.
    const lines = [info.title, message]
    if (origin) lines.push(origin)
    await copyText(lines.join('\n')).catch(() => {})
    copied = true
    window.clearTimeout(copiedTimer)
    copiedTimer = window.setTimeout(() => (copied = false), 2000)
  }

  const remedyText: Record<Remedy, { title: string; detail: string }> = {
    'network-settings': {
      title: 'Open Network settings',
      detail: 'Add a CA or client certificate; it applies to every request.'
    },
    'skip-tls': {
      title: 'Send once without verifying the certificate',
      detail: 'Sends this one request unverified. The request itself is left unchanged.'
    }
  }

  function runRemedy(remedy: Remedy): void {
    if (remedy === 'network-settings') onOpenNetwork?.()
    else onSkipTls?.()
  }

  const available = $derived(
    info.remedies.filter((remedy) => (remedy === 'network-settings' ? onOpenNetwork : onSkipTls))
  )
</script>

<div
  data-role="error"
  role="alert"
  class="flex min-h-0 flex-1 flex-col gap-4 overflow-auto p-5"
>
  <div class="flex items-start gap-3">
    <span
      class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-danger/15 text-danger"
      aria-hidden="true"
    >
      <svg viewBox="0 0 24 24" class="h-[18px] w-[18px]" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <path d="M18 6 6 18M6 6l12 12" />
      </svg>
    </span>
    <div class="min-w-0">
      <h2 class="text-base font-semibold text-fg">{info.title}</h2>
      <p class="mt-1 text-sm leading-relaxed text-fg-muted">{info.summary}</p>
    </div>
  </div>

  {#if rows.length > 0}
    <ol data-role="error-stages" class="flex items-stretch gap-1.5" aria-label="Connection stages">
      {#each rows as row, index (row.key)}
        <li
          data-state={row.state}
          class="flex min-w-0 flex-1 flex-col items-center gap-1 rounded-lg border px-2 py-2 text-center
                 {row.state === 'fail'
            ? 'border-danger/60 bg-danger/10'
            : 'border-line bg-base'}"
        >
          <span
            class="text-xs font-semibold {row.state === 'fail'
              ? 'text-danger'
              : row.state === 'ok'
                ? 'text-fg'
                : 'text-fg-faint'}"
          >
            {row.state === 'ok' ? '✓' : row.state === 'fail' ? '✕' : '·'}
            {row.label}
          </span>
          <span class="tabular font-mono text-[11px] text-fg-muted">
            {#if row.state === 'fail'}
              failed{row.ms === undefined ? '' : ` · ${formatProbeMs(row.ms)}`}
            {:else if row.state === 'idle'}
              not reached
            {:else if row.ms !== undefined}
              {formatProbeMs(row.ms)}
            {:else}
              reached
            {/if}
          </span>
        </li>
        {#if index < rows.length - 1}
          <li aria-hidden="true" class="flex items-center text-fg-faint">›</li>
        {/if}
      {/each}
    </ol>
  {/if}

  {#if available.length > 0}
    <div class="flex flex-col gap-2">
      {#each available as remedy (remedy)}
        <button
          type="button"
          data-role="error-remedy"
          onclick={() => runRemedy(remedy)}
          class="flex flex-col gap-0.5 rounded-lg border border-line bg-base px-3 py-2.5 text-left transition hover:border-accent/60"
        >
          <span class="text-sm font-semibold text-fg">{remedyText[remedy].title}</span>
          <span class="text-xs text-fg-muted">{remedyText[remedy].detail}</span>
        </button>
      {/each}
    </div>
  {/if}

  {#if info.hints.length > 0}
    <ul class="list-disc space-y-1 pl-5 text-sm text-fg-muted">
      {#each info.hints as hint (hint)}
        <li>{hint}</li>
      {/each}
    </ul>
  {/if}

  <div class="flex flex-wrap items-center gap-2">
    <button
      type="button"
      data-role="error-retry"
      onclick={onRetry}
      class="rounded-lg bg-accent px-3.5 py-2 text-sm font-medium text-white transition hover:brightness-110"
    >
      Try again <span class="ml-1 font-mono text-xs font-normal opacity-80">{sendKey}</span>
    </button>
    {#if origin}
      <button
        type="button"
        onclick={() => void check()}
        disabled={probing}
        class="rounded-lg border border-line px-3 py-2 text-sm text-fg transition hover:bg-line/60 disabled:opacity-50"
      >
        {probing ? 'Checking…' : 'Run connection check'}
      </button>
    {/if}
    <button
      type="button"
      onclick={() => void copy()}
      class="rounded-lg border border-line px-3 py-2 text-sm text-fg transition hover:bg-line/60"
    >
      {copied ? 'Copied' : 'Copy details'}
    </button>
  </div>

  {#if probe && !probe.failedStage && !probe.error}
    <p class="text-xs text-fg-muted" role="status">
      The connection check got through to {probe.connectedTo}. The problem is past the handshake.
    </p>
  {/if}
  {#if probeFailure}
    <p class="text-xs text-warning" role="status">The connection check failed: {probeFailure}</p>
  {/if}

  <details class="mt-auto border-t border-line pt-3 text-xs text-fg-muted">
    <summary class="cursor-pointer select-none">Technical details</summary>
    <pre
      class="mt-2 whitespace-pre-wrap break-words rounded-md bg-base px-2.5 py-2 font-mono text-xs leading-relaxed"
    >{message}{probe?.error ? `\nconnection check: ${probe.error}` : ''}</pre>
  </details>
</div>
