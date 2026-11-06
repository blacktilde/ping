/**
 * The performance benchmark's in-page half (docs/PERF.md). Bundled only when the renderer is built
 * with `VITE_PING_BENCH=1`; `main.ts` drops the import otherwise, so a release never carries it.
 *
 * It runs the same scenarios in whichever shell hosts it and times them with `performance.now()`.
 * Results leave through the app's own `http.send` to a loopback collector run by
 * `desktop/bench/bench.mjs`, so neither shell needs a hook of its own. The collector holds each
 * phase's reply until it has sampled memory and CPU from outside, which is how the two halves
 * stay in step.
 */
import { EditorView } from '@codemirror/view'

const COLLECTOR = 'http://127.0.0.1:8799'

interface Plan {
  /** Startup runs stop after `ready`; full runs go through every scenario. */
  mode: 'startup' | 'full'
}

async function post<T = unknown>(phase: string, data: unknown = {}): Promise<T> {
  const result = await window.ping.request<{ body: { content: string | null } }>('http.send', {
    method: 'POST',
    url: `${COLLECTOR}/phase/${phase}`,
    body: { type: 'json', content: JSON.stringify(data) },
    timeoutMs: 600_000
  })
  if (!result.ok) {
    throw new Error(`collector: ${result.error.message}`)
  }
  return JSON.parse(result.value.body.content ?? 'null') as T
}

const frame = (): Promise<number> => new Promise((resolve) => requestAnimationFrame(resolve))

/** After the next frame has been produced: rAF, then a task queued behind it. */
async function painted(): Promise<void> {
  await frame()
  await new Promise<void>((resolve) => {
    const channel = new MessageChannel()
    channel.port1.onmessage = () => resolve()
    channel.port2.postMessage(null)
  })
}

async function until(test: () => boolean, timeoutMs: number, what: string): Promise<void> {
  const deadline = performance.now() + timeoutMs
  while (!test()) {
    if (performance.now() > deadline) {
      throw new Error(`timed out waiting for ${what}`)
    }
    await frame()
  }
}

function stats(samples: number[]): Record<string, number> {
  const sorted = [...samples].sort((a, b) => a - b)
  const at = (q: number): number => sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))]
  const sum = sorted.reduce((a, b) => a + b, 0)
  return {
    n: sorted.length,
    mean: sum / sorted.length,
    p50: at(0.5),
    p95: at(0.95),
    max: sorted[sorted.length - 1]
  }
}

/** Records every frame interval until stopped: the UI thread's own view of jank. */
function frameRecorder(): () => number[] {
  const gaps: number[] = []
  let last = performance.now()
  let running = true
  const tick = (now: number): void => {
    gaps.push(now - last)
    last = now
    if (running) {
      requestAnimationFrame(tick)
    }
  }
  requestAnimationFrame((now) => {
    last = now
    requestAnimationFrame(tick)
  })
  return () => {
    running = false
    return gaps
  }
}

function frameSummary(gaps: number[]): Record<string, number> {
  return { ...stats(gaps), over50ms: gaps.filter((g) => g > 50).length, over100ms: gaps.filter((g) => g > 100).length }
}

const $ = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector)
const sendButton = (): HTMLButtonElement | null => $('button[type=submit]')
const tabs = (): HTMLElement[] => [...document.querySelectorAll<HTMLElement>('[data-role="request-tab"]')]
const activeTab = (): HTMLElement | undefined => tabs().find((t) => t.getAttribute('aria-selected') === 'true')

function setInput(label: string, value: string): void {
  const inputs = document.querySelectorAll<HTMLInputElement>(`input[aria-label="${label}"]`)
  const input = inputs[inputs.length - 1]
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
  setter.call(input, value)
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

async function newTab(): Promise<number> {
  const before = tabs().length
  const start = performance.now()
  $<HTMLButtonElement>('button[aria-label="New request tab"]')!.click()
  await until(() => tabs().length === before + 1, 5_000, 'a new tab')
  await painted()
  return performance.now() - start
}

// ---------------------------------------------------------------------------------------------

async function ipc(): Promise<Record<string, unknown>> {
  const timed = async (count: number, call: () => Promise<unknown>): Promise<number[]> => {
    const samples: number[] = []
    for (let i = 0; i < count; i++) {
      const start = performance.now()
      await call()
      samples.push(performance.now() - start)
    }
    return samples
  }

  // Warm both paths so the first JIT and allocation costs are not in the samples.
  await timed(100, () => window.ping.runtime.list())
  await timed(100, () => window.ping.request('core.ping', { message: 'x' }))

  // WebKit rounds performance.now() to whole milliseconds, so a sub-millisecond call is timed as a
  // loop of 1000 and divided; the per-call distribution is kept for its tail.
  const loop = async (call: () => Promise<unknown>): Promise<Record<string, number>> => {
    const start = performance.now()
    const samples = await timed(1000, call)
    return { perCallMs: (performance.now() - start) / 1000, ...stats(samples) }
  }
  const shellOnly = await loop(() => window.ping.runtime.list())
  const coreSmall = await loop(() => window.ping.request('core.ping', { message: 'x' }))

  const parallelStart = performance.now()
  await Promise.all(Array.from({ length: 1000 }, () => window.ping.request('core.ping', { message: 'x' })))
  const parallelMs = performance.now() - parallelStart

  // Echoed both ways: renderer → shell → core and back, so each call moves twice the payload.
  const payloads: Record<string, unknown> = {}
  for (const [label, bytes] of [['100KB', 100 * 1024], ['1MB', 1024 * 1024], ['9MB', 9 * 1024 * 1024]] as const) {
    const message = 'a'.repeat(bytes)
    const samples = await timed(5, async () => {
      const result = await window.ping.request<{ message: string }>('core.ping', { message })
      if (!result.ok || result.value.message.length !== bytes) {
        throw new Error('echo mismatch')
      }
    })
    const summary = stats(samples)
    payloads[label] = { ...summary, mbPerSecond: (2 * bytes) / (1024 * 1024) / (summary.p50 / 1000) }
  }

  return { shellOnly, coreSmall, parallel1000: { totalMs: parallelMs, callsPerSecond: 1000 / (parallelMs / 1000) }, payloads }
}

async function renderResponse(label: string, path: string): Promise<Record<string, unknown>> {
  const tabMs = await newTab()
  setInput('Request URL', `${COLLECTOR}${path}`)
  await painted()
  await until(() => sendButton()?.dataset.state === 'idle', 5_000, 'Send to be idle')

  const stop = frameRecorder()
  const start = performance.now()
  sendButton()!.click()
  let receivedAt = 0
  await until(
    () => {
      const status = $('[data-role="response"] header span')?.textContent?.trim()
      if (status?.startsWith('200') && !receivedAt) {
        receivedAt = performance.now()
      }
      return (
        !!status?.startsWith('200') &&
        !$('[data-role="formatting"]') &&
        !!$('[data-role="response"] .cm-content[aria-label="Response body, pretty"]')
      )
    },
    120_000,
    `the ${label} response to render`
  )
  await painted()
  const renderedMs = performance.now() - start
  const loadFrames = frameSummary(stop())

  // Scroll the pretty view 400px a frame for 120 frames and watch the frame times.
  const scroller = $('[data-role="response"] .cm-scroller')!
  const scrollStop = frameRecorder()
  for (let i = 0; i < 120; i++) {
    scroller.scrollTop += 400
    await frame()
  }
  const scrollFrames = frameSummary(scrollStop())

  return {
    newTabMs: tabMs,
    statusShownMs: receivedAt - start,
    renderedMs,
    loadFrames,
    scrollFrames,
    fps: 1000 / (scrollFrames.mean as number)
  }
}

async function switching(): Promise<Record<string, unknown>> {
  // Twenty more tabs, then alternate between the heavy-response tab and a light one.
  const opened: number[] = []
  for (let i = 0; i < 20; i++) {
    opened.push(await newTab())
  }
  const all = tabs()
  const heavy = all[2] // Opened by the 9 MB render, after the starter tab and the 1 MB one.
  const light = all[all.length - 1]
  const samples: number[] = []
  for (let i = 0; i < 20; i++) {
    const target = i % 2 === 0 ? heavy : light
    const start = performance.now()
    target.click()
    await until(() => activeTab() === target, 5_000, 'the tab to activate')
    await painted()
    samples.push(performance.now() - start)
  }
  return { newTab: stats(opened), switchHeavyLight: stats(samples), tabs: all.length }
}

/** Real key presses come from the harness (xdotool); this side only times them to the next frame. */
async function typing(label: string, document_: string): Promise<Record<string, unknown>> {
  await newTab()
  const mode = $<HTMLSelectElement>('select[aria-label="Body mode"]')
  if (!mode) {
    ;[...document.querySelectorAll<HTMLElement>('[data-role="request"] [role=tab]')]
      .find((t) => t.textContent?.trim().startsWith('Body'))
      ?.click()
    await until(() => !!$('select[aria-label="Body mode"]'), 5_000, 'the Body tab')
  }
  const select = $<HTMLSelectElement>('select[aria-label="Body mode"]')!
  select.value = 'json'
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await until(() => !!$('[data-role="request"] .cm-editor'), 5_000, 'the body editor')
  const view = EditorView.findFromDOM($('[data-role="request"] .cm-editor')!)!
  view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: document_ } })
  view.dispatch({ selection: { anchor: Math.floor(view.state.doc.length / 2) } })
  view.focus()
  await painted()

  const before = view.state.doc.length
  const latencies: number[] = []
  const onKey = (event: KeyboardEvent): void => {
    const pressed = event.timeStamp
    requestAnimationFrame(() => {
      const channel = new MessageChannel()
      channel.port1.onmessage = () => latencies.push(performance.now() - pressed)
      channel.port2.postMessage(null)
    })
  }
  window.addEventListener('keydown', onKey, true)
  await post(`typing-ready`, { label })
  window.removeEventListener('keydown', onKey, true)
  await painted()
  return { ...stats(latencies), typed: view.state.doc.length - before, docBytes: document_.length }
}

function jsonDocument(bytes: number): string {
  const rows: string[] = []
  let size = 2
  for (let i = 0; size < bytes; i++) {
    const row = `  {"id": ${i}, "name": "user ${i}", "email": "user${i}@example.com", "active": ${i % 2 === 0}}`
    rows.push(row)
    size += row.length + 2
  }
  return `[\n${rows.join(',\n')}\n]`
}

async function main(): Promise<void> {
  await until(
    () => sendButton()?.dataset.state === 'idle' && !!$('[data-role="sidebar"]')?.textContent?.includes('Get started'),
    120_000,
    'the UI to be ready'
  )
  await painted()
  const readyAt = Date.now()
  const nav = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined
  const plan = await post<Plan>('ready', {
    readyAt,
    timeOrigin: performance.timeOrigin,
    domContentLoaded: nav?.domContentLoadedEventEnd ?? null,
    load: nav?.loadEventEnd ?? null,
    paint: performance.getEntriesByType('paint').map((e) => ({ name: e.name, at: e.startTime })),
    userAgent: navigator.userAgent
  })
  if (plan.mode !== 'full') {
    return
  }

  await post('ipc', await ipc())
  await post('render-1mb', await renderResponse('1 MB', '/data/1mb.json'))
  await post('render-9mb', await renderResponse('9 MB', '/data/9mb.json'))
  await post('tabs', await switching())
  await post('typing-small', await typing('small', '{\n  "name": "ping"\n}'))
  await post('typing-1mb', await typing('1mb', jsonDocument(1024 * 1024)))
  await post('done')
}

main().catch((error: unknown) => {
  void post('error', { message: error instanceof Error ? `${error.message}\n${error.stack}` : String(error) })
})
