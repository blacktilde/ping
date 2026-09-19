<script lang="ts">
  import type { HttpTiming } from '../lib/http'
  import { formatBytes, formatDuration } from '../lib/format'

  interface Props {
    timing: HttpTiming
    bytes: number
  }

  let { timing, bytes }: Props = $props()

  const dns = $derived(timing.dnsMs ?? 0)
  const span = $derived(dns + timing.ttfbMs + timing.downloadMs)

  function width(value: number): string {
    return span > 0 ? `${(value / span) * 100}%` : '0%'
  }
</script>

<div class="h-full overflow-auto p-4">
  <div class="flex h-3 w-full overflow-hidden rounded-full bg-base" aria-hidden="true">
    <div class="bg-sky-500/70" style="width: {width(dns)}"></div>
    <div class="bg-accent/80" style="width: {width(timing.ttfbMs)}"></div>
    <div class="bg-emerald-500/70" style="width: {width(timing.downloadMs)}"></div>
  </div>

  <dl class="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-4">
    <div>
      <dt class="flex items-center gap-1.5 text-xs text-neutral-500">
        <span class="h-2 w-2 rounded-full bg-sky-500/70"></span> DNS
      </dt>
      <dd class="mt-0.5 font-mono text-neutral-300">
        {timing.dnsMs == null ? '—' : formatDuration(timing.dnsMs)}
      </dd>
    </div>
    <div>
      <dt class="flex items-center gap-1.5 text-xs text-neutral-500">
        <span class="h-2 w-2 rounded-full bg-accent/80"></span> Waiting
      </dt>
      <dd class="mt-0.5 font-mono text-neutral-300">{formatDuration(timing.ttfbMs)}</dd>
    </div>
    <div>
      <dt class="flex items-center gap-1.5 text-xs text-neutral-500">
        <span class="h-2 w-2 rounded-full bg-emerald-500/70"></span> Download
      </dt>
      <dd class="mt-0.5 font-mono text-neutral-300">{formatDuration(timing.downloadMs)}</dd>
    </div>
    <div>
      <dt class="text-xs text-neutral-500">Total</dt>
      <dd class="mt-0.5 font-mono text-neutral-300">{formatDuration(timing.totalMs)}</dd>
    </div>
  </dl>

  <p class="mt-4 text-xs text-neutral-500">Size: <span class="font-mono">{formatBytes(bytes)}</span></p>

  <p class="mt-4 max-w-prose text-xs leading-relaxed text-neutral-600">
    DNS resolves before dispatch, so it is not part of Total. Waiting covers connection,
    TLS and the server together: <code>java.net.http</code> exposes no finer split, and an
    invented one would be worse than a coarse bar.
  </p>
</div>
