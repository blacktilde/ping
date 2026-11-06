#!/usr/bin/env node
/**
 * Turns `bench.mjs` results into the Markdown tables in docs/PERF.md.
 *
 *   node bench/report.mjs bench/results.json
 *
 * Every cell is the median across runs, with the range in brackets when there is more than one.
 */
import { readFileSync } from 'node:fs'

const results = JSON.parse(readFileSync(process.argv[2] ?? 'bench/results.json', 'utf8'))
const names = Object.keys(results.shells)

const median = (values) => {
  const sorted = values.filter((v) => typeof v === 'number').sort((a, b) => a - b)
  if (!sorted.length) return null
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

function cell(values, digits = 0, unit = '') {
  const present = values.filter((v) => typeof v === 'number')
  if (!present.length) return '–'
  const f = (v) => `${v.toFixed(digits)}${unit}`
  const m = median(present)
  if (present.length === 1) return f(m)
  return `${f(m)} (${f(Math.min(...present))}–${f(Math.max(...present))})`
}

function table(title, rows) {
  const lines = [`#### ${title}`, '', `| | ${names.join(' | ')} |`, `|---|${names.map(() => '---').join('|')}|`]
  for (const [label, pick, digits, unit] of rows) {
    lines.push(`| ${label} | ${names.map((name) => cell(pick(results.shells[name]), digits, unit)).join(' | ')} |`)
  }
  return `${lines.join('\n')}\n`
}

const runs = (key, f) => (shell) => shell[key].map(f)
const full = (f) => (shell) => shell.full.map((run) => {
  try {
    return f(run)
  } catch {
    return null
  }
})

const out = []
out.push(`Machine: ${results.machine.cpu}, ${results.machine.cores} cores, ${results.machine.memoryGB.toFixed(0)} GB, Linux ${results.machine.kernel}. Started ${results.startedAt}.\n`)
out.push(
  table('Startup, warm (ms from spawn)', [
    ['Webview navigation starts', runs('warm', (r) => r.navigationStartMs)],
    ['DOM content loaded', runs('warm', (r) => r.domContentLoadedMs)],
    ['First paint', runs('warm', (r) => r.firstPaintMs)],
    ['UI ready (collection listed, Send idle)', runs('warm', (r) => r.readyMs)]
  ])
)
out.push(
  table('Startup, cold: page cache dropped (ms from spawn)', [
    ['Webview navigation starts', runs('cold', (r) => r.navigationStartMs)],
    ['DOM content loaded', runs('cold', (r) => r.domContentLoadedMs)],
    ['UI ready', runs('cold', (r) => r.readyMs)]
  ])
)
out.push(
  table('Memory: shell processes, core excluded (MB)', [
    ['Idle PSS', full((r) => r.memory.idle.shellPssMB)],
    ['Idle private (anonymous) PSS', full((r) => r.memory.idle.shellAnonMB)],
    ['After a 1 MB response', full((r) => r.memory['after-render-1mb'].shellPssMB)],
    ['After a 9 MB response', full((r) => r.memory['after-render-9mb'].shellPssMB)],
    ['End: 23 tabs, 1 MB body typed into', full((r) => r.memory.end.shellPssMB)],
    ['Processes', full((r) => r.memory.idle.processes)],
    ['Threads', full((r) => r.memory.idle.threads)],
    ['Core PSS at idle, for scale', full((r) => r.memory.idle.corePssMB)]
  ])
)
out.push(
  table('CPU: shell processes, % of one core', [
    ['Idle, 10 s after ready', full((r) => r.cpu.idlePercent), 1],
    ['Idle after the heavy work', full((r) => r.cpu.endIdlePercent), 1]
  ])
)
const ipc = (f) => full((r) => f(r.phases.ipc))
out.push(
  table('IPC (ms unless noted)', [
    ['Renderer ↔ shell round trip', ipc((i) => i.shellOnly.perCallMs), 3],
    ['Renderer ↔ core round trip (`core.ping`)', ipc((i) => i.coreSmall.perCallMs), 3],
    ['… p95', ipc((i) => i.coreSmall.p95), 1],
    ['1000 concurrent `core.ping`, total', ipc((i) => i.parallel1000.totalMs)],
    ['Echo 100 KB', ipc((i) => i.payloads['100KB'].p50), 1],
    ['Echo 1 MB', ipc((i) => i.payloads['1MB'].p50)],
    ['Echo 9 MB', ipc((i) => i.payloads['9MB'].p50)],
    ['Echo 9 MB throughput (MB/s, both ways)', ipc((i) => i.payloads['9MB'].mbPerSecond)]
  ])
)
const phase = (key, f) => full((r) => f(r.phases[key]))
out.push(
  table('Large responses (ms unless noted)', [
    ['1 MB: Send to status shown', phase('render-1mb', (p) => p.statusShownMs)],
    ['1 MB: Send to pretty view painted', phase('render-1mb', (p) => p.renderedMs)],
    ['1 MB: longest frame while loading', phase('render-1mb', (p) => p.loadFrames.max)],
    ['1 MB: scroll fps', phase('render-1mb', (p) => p.fps), 1],
    ['1 MB: scroll p95 frame', phase('render-1mb', (p) => p.scrollFrames.p95), 1],
    ['9 MB: Send to status shown', phase('render-9mb', (p) => p.statusShownMs)],
    ['9 MB: Send to pretty view painted', phase('render-9mb', (p) => p.renderedMs)],
    ['9 MB: longest frame while loading', phase('render-9mb', (p) => p.loadFrames.max)],
    ['9 MB: scroll fps', phase('render-9mb', (p) => p.fps), 1],
    ['9 MB: scroll p95 frame', phase('render-9mb', (p) => p.scrollFrames.p95), 1]
  ])
)
out.push(
  table('Tabs and typing (ms)', [
    ['New tab, p50', phase('tabs', (p) => p.newTab.p50), 1],
    ['Switch heavy ↔ light tab, p50', phase('tabs', (p) => p.switchHeavyLight.p50), 1],
    ['Switch heavy ↔ light tab, p95', phase('tabs', (p) => p.switchHeavyLight.p95), 1],
    ['Key to next frame, small body, p50', phase('typing-small', (p) => p.p50), 1],
    ['Key to next frame, small body, p95', phase('typing-small', (p) => p.p95), 1],
    ['Key to next frame, 1 MB body, p50', phase('typing-1mb', (p) => p.p50), 1],
    ['Key to next frame, 1 MB body, p95', phase('typing-1mb', (p) => p.p95), 1],
    ['Keys that arrived (of 60), 1 MB body', phase('typing-1mb', (p) => p.typed)]
  ])
)
out.push(`First launch (new profile), UI ready: ${names.map((n) => `${n} ${Math.round(results.shells[n].firstLaunch.readyMs)} ms`).join(', ')}.\n`)
out.push(`Engines: ${names.map((n) => `${n}: \`${results.shells[n].firstLaunch.userAgent}\``).join('; ')}\n`)
process.stdout.write(out.join('\n'))
