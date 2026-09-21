<script lang="ts">
  import type { HttpTiming } from '../lib/http'
  import { formatBytes, formatDuration, formatProbeMs } from '../lib/format'
  import { expiryNote, type ProbeResult } from '../lib/probe'

  interface Props {
    timing: HttpTiming
    bytes: number
    /** scheme://host:port of the request; without it there is nothing to probe. */
    origin?: string
    probe?: ProbeResult | null
    probeError?: string
    probing?: boolean
    onProbe?: () => void
  }

  let { timing, bytes, origin, probe = null, probeError = '', probing = false, onProbe }: Props = $props()

  const stages = $derived.by(() => {
    if (!probe) return []
    const rows: { key: string; label: string; ms: number | undefined }[] = [
      { key: 'dns', label: probe.viaProxy ? 'DNS (proxy)' : 'DNS', ms: probe.dnsMs },
      { key: 'connect', label: probe.viaProxy ? 'TCP (to proxy)' : 'TCP connect', ms: probe.connectMs },
      { key: 'tunnel', label: 'Proxy tunnel', ms: probe.tunnelMs },
      { key: 'tls', label: 'TLS handshake', ms: probe.tlsMs }
    ]
    return rows.filter((row) => row.ms !== undefined)
  })
  const expiry = $derived(probe?.certificate ? expiryNote(probe.certificate.notAfter) : null)

  const dns = $derived(timing.dnsMs ?? 0)
  const span = $derived(dns + timing.ttfbMs + timing.downloadMs)

  function width(value: number): string {
    return span > 0 ? `${(value / span) * 100}%` : '0%'
  }
</script>

<div class="h-full overflow-auto p-5">
  <div class="flex h-3 w-full overflow-hidden rounded-full bg-base" aria-hidden="true">
    <div class="bg-accent/70" style="width: {width(dns)}"></div>
    <div class="bg-accent/80" style="width: {width(timing.ttfbMs)}"></div>
    <div class="bg-success/70" style="width: {width(timing.downloadMs)}"></div>
  </div>

  <dl class="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-4">
    <div>
      <dt class="flex items-center gap-1.5 text-xs text-fg-muted">
        <span class="h-2 w-2 rounded-full bg-accent/70"></span> DNS
      </dt>
      <dd class="mt-0.5 font-mono text-fg">
        {timing.dnsMs == null ? '—' : formatDuration(timing.dnsMs)}
      </dd>
    </div>
    <div>
      <dt class="flex items-center gap-1.5 text-xs text-fg-muted">
        <span class="h-2 w-2 rounded-full bg-accent/80"></span> Waiting
      </dt>
      <dd class="mt-0.5 font-mono text-fg">{formatDuration(timing.ttfbMs)}</dd>
    </div>
    <div>
      <dt class="flex items-center gap-1.5 text-xs text-fg-muted">
        <span class="h-2 w-2 rounded-full bg-success/70"></span> Download
      </dt>
      <dd class="mt-0.5 font-mono text-fg">{formatDuration(timing.downloadMs)}</dd>
    </div>
    <div>
      <dt class="text-xs text-fg-muted">Total</dt>
      <dd class="mt-0.5 font-mono text-fg">{formatDuration(timing.totalMs)}</dd>
    </div>
  </dl>

  <p class="mt-4 text-xs text-fg-muted">Size: <span class="font-mono">{formatBytes(bytes)}</span></p>

  <p class="mt-4 max-w-prose text-xs leading-relaxed text-fg-faint">
    DNS resolves before dispatch, so it is not part of Total. Waiting covers connection,
    TLS and the server together: <code>java.net.http</code> exposes no finer split, and an
    invented one would be worse than a coarse bar. To see the stages, measure the connection
    below.
  </p>

  <section data-role="probe" class="mt-6 border-t border-line pt-4">
    <div class="flex items-center gap-3">
      <h3 class="text-xs font-medium uppercase tracking-wide text-fg-muted">Connection probe</h3>
      <button
        type="button"
        data-role="probe-run"
        disabled={!origin || probing}
        onclick={() => onProbe?.()}
        class="rounded-md border border-line px-2 py-1 text-xs text-fg-muted transition
               hover:border-fg-muted hover:text-fg disabled:opacity-50"
      >
        {probing ? 'Measuring…' : probe || probeError ? 'Measure again' : 'Measure connection'}
      </button>
    </div>
    <p class="mt-2 max-w-prose text-xs leading-relaxed text-fg-faint">
      Opens a separate connection to <span class="font-mono">{origin ?? 'the host'}</span> just now and
      times it. It is a different connection from the one above, so it is not part of Total and can
      differ from it.
    </p>

    {#if probeError}
      <p data-role="probe-error" class="mt-3 text-sm text-danger">{probeError}</p>
    {/if}

    {#if probe}
      <dl data-role="probe-result" class="mt-3 grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-4">
        {#each stages as stage (stage.key)}
          <div data-stage={stage.key} data-failed={probe.failedStage === stage.key}>
            <dt class="text-xs text-fg-muted">{stage.label}</dt>
            <dd class="mt-0.5 font-mono {probe.failedStage === stage.key ? 'text-danger' : 'text-fg'}">
              {formatProbeMs(stage.ms ?? 0)}
            </dd>
          </div>
        {/each}
      </dl>

      {#if probe.failedStage}
        <p data-role="probe-failure" class="mt-3 text-sm text-danger">
          Stopped at {probe.failedStage}: {probe.error}
        </p>
      {/if}

      <p class="mt-3 text-xs text-fg-muted">
        Connected to <span class="font-mono">{probe.connectedTo}</span>{probe.viaProxy ? ' (the proxy)' : ''}
        {#if probe.addresses?.length}
          · <span class="font-mono">{probe.addresses.join(', ')}</span>
        {/if}
      </p>

      {#if probe.protocol}
        <p data-role="probe-tls" class="mt-1 text-xs text-fg-muted">
          <span class="font-mono">{probe.protocol}</span>
          {#if probe.alpn}· <span class="font-mono">{probe.alpn}</span>{/if}
          {#if probe.cipherSuite}· <span class="font-mono">{probe.cipherSuite}</span>{/if}
        </p>
      {/if}

      {#if probe.certificate}
        <div data-role="probe-certificate" class="mt-3 rounded-md border border-line p-3 text-xs">
          <p class="font-mono text-fg">{probe.certificate.subject}</p>
          <p class="mt-1 text-fg-muted">Issued by <span class="font-mono">{probe.certificate.issuer}</span></p>
          <p class="mt-1 text-fg-muted">
            Valid until {new Date(probe.certificate.notAfter).toLocaleDateString()}
            {#if expiry}<span class={expiry.warn ? 'text-warning' : ''}> ({expiry.text})</span>{/if}
            · {probe.verified ? 'verified' : 'not verified'}
          </p>
          {#if probe.certificate.altNames?.length}
            <p class="mt-1 break-words text-fg-faint">{probe.certificate.altNames.join(', ')}</p>
          {/if}
        </div>
      {/if}
    {/if}
  </section>
</div>
