#!/usr/bin/env node
/**
 * Benchmarks the Electron and Tauri shells on the same renderer and core (docs/PERF.md).
 *
 *   node bench/bench.mjs [--startup N] [--cold N] [--full N] [--only name,name] [--out file.json]
 *
 * Needs bench builds of both shells (VITE_PING_BENCH=1, see docs/PERF.md), an X display in
 * $DISPLAY, xdotool, and the JVM core. Linux only: memory and CPU come from /proc.
 *
 * The in-page half (`src/renderer/src/bench/bench.ts`) reports each phase by POSTing to the
 * collector below through the app's own `http.send`. The collector holds the reply while this
 * side samples the shell from outside, then releases it, so the page never runs ahead of a sample.
 */
import { execFileSync, spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const desktop = resolve(here, '..')
const repo = resolve(desktop, '..')

const args = process.argv.slice(2)
const option = (name, fallback) => {
  const at = args.indexOf(`--${name}`)
  return at >= 0 ? args[at + 1] : fallback
}
const STARTUP_RUNS = Number(option('startup', 10))
const COLD_RUNS = Number(option('cold', 5))
const FULL_RUNS = Number(option('full', 3))
const OUT = resolve(option('out', join(desktop, 'bench', 'results.json')))
const work = mkdtempSync(join(option('tmp', tmpdir()), 'ping-bench-'))

const CORE = process.env.PING_CORE_BIN ?? join(repo, 'core/build/install/ping-core/bin/ping-core')

const SHELLS = {
  electron: {
    command: [join(desktop, 'dist/linux-unpacked/ping'), '--no-sandbox'],
    env: {}
  },
  tauri: {
    command: [join(desktop, 'src-tauri/target/release/ping-shell')],
    env: {}
  },
  'tauri-nodmabuf': {
    command: [join(desktop, 'src-tauri/target/release/ping-shell')],
    env: { WEBKIT_DISABLE_DMABUF_RENDERER: '1' }
  }
}
const only = option('only', null)?.split(',')
const shells = Object.entries(SHELLS).filter(([name]) => !only || only.includes(name))

// --- Collector ---------------------------------------------------------------------------------

function jsonDocument(bytes) {
  const rows = []
  let size = 2
  for (let i = 0; size < bytes; i++) {
    const row = `  {"id": ${i}, "name": "user ${i}", "email": "user${i}@example.com", "active": ${i % 2 === 0}}`
    rows.push(row)
    size += row.length + 2
  }
  return `[\n${rows.join(',\n')}\n]`
}
const DATA = { '1mb.json': jsonDocument(1024 * 1024), '9mb.json': jsonDocument(9 * 1024 * 1024) }

let onPhase = null
const server = createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1')
  if (request.method === 'GET' && url.pathname.startsWith('/data/')) {
    const body = DATA[url.pathname.slice('/data/'.length)]
    response.writeHead(body ? 200 : 404, { 'content-type': 'application/json' })
    response.end(body ?? '')
    return
  }
  const match = url.pathname.match(/^\/phase\/([\w-]+)$/)
  if (request.method !== 'POST' || !match) {
    response.writeHead(404).end()
    return
  }
  let raw = ''
  request.setEncoding('utf8')
  request.on('data', (chunk) => (raw += chunk))
  request.on('end', async () => {
    if (process.env.BENCH_DEBUG) process.stderr.write(`phase ${match[1]}\n`)
    const reply = (await onPhase?.(match[1], raw ? JSON.parse(raw) : {})) ?? {}
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify(reply))
  })
})
await new Promise((ready) => server.listen(8799, '127.0.0.1', ready))

// --- Process sampling --------------------------------------------------------------------------

/** The process and all its descendants, from every process's parent id (children of any thread). */
function tree(root) {
  const parents = new Map()
  for (const entry of readdirSync('/proc')) {
    if (!/^\d+$/.test(entry)) continue
    try {
      const stat = readFileSync(`/proc/${entry}/stat`, 'utf8')
      parents.set(Number(entry), Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[1]))
    } catch {}
  }
  if (!parents.has(root)) return []
  const found = [root]
  for (let i = 0; i < found.length; i++) {
    for (const [pid, parent] of parents) {
      if (parent === found[i]) found.push(pid)
    }
  }
  return found
}

const isCore = (pid) => {
  try {
    const cmd = readFileSync(`/proc/${pid}/cmdline`, 'utf8')
    return cmd.includes('java') || cmd.includes('ping-core')
  } catch {
    return false
  }
}

function field(text, name) {
  const match = text.match(new RegExp(`^${name}:\\s+(\\d+)`, 'm'))
  return match ? Number(match[1]) : 0
}

/** PSS (shared pages split between their users) for the shell's processes, core separately. */
function memory(root) {
  const result = { shellPssMB: 0, shellRssMB: 0, shellAnonMB: 0, corePssMB: 0, processes: 0, threads: 0 }
  for (const pid of tree(root)) {
    let rollup
    try {
      rollup = readFileSync(`/proc/${pid}/smaps_rollup`, 'utf8')
    } catch {
      continue
    }
    const pss = field(rollup, 'Pss') / 1024
    if (isCore(pid)) {
      result.corePssMB += pss
      continue
    }
    result.shellPssMB += pss
    result.shellRssMB += field(rollup, 'Rss') / 1024
    result.shellAnonMB += field(rollup, 'Pss_Anon') / 1024
    result.processes += 1
    try {
      result.threads += readdirSync(`/proc/${pid}/task`).length
    } catch {}
  }
  return result
}

const TICKS = Number(execFileSync('getconf', ['CLK_TCK']).toString().trim())
function cpuTicks(root) {
  let total = 0
  for (const pid of tree(root)) {
    if (isCore(pid)) continue
    try {
      const stat = readFileSync(`/proc/${pid}/stat`, 'utf8')
      const fields = stat.slice(stat.lastIndexOf(')') + 2).split(' ')
      total += Number(fields[11]) + Number(fields[12]) // utime + stime
    } catch {}
  }
  return total
}

async function cpuPercent(root, ms) {
  const before = cpuTicks(root)
  await sleep(ms)
  return ((cpuTicks(root) - before) / TICKS / (ms / 1000)) * 100
}

const sleep = (ms) => new Promise((done) => setTimeout(done, ms))

// --- Running a shell ---------------------------------------------------------------------------

function prepareWorkspace() {
  const root = join(work, 'workspace')
  const line = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'store.scaffold', params: { root, collection: 'My Collection' } })
  execFileSync(CORE, [], { input: `${line}\n`, stdio: ['pipe', 'ignore', 'ignore'] })
  return root
}

function launch(name, home, workspace) {
  const shell = SHELLS[name]
  mkdirSync(home, { recursive: true })
  const env = {
    ...process.env,
    ...shell.env,
    HOME: home,
    XDG_CONFIG_HOME: join(home, '.config'),
    XDG_CACHE_HOME: join(home, '.cache'),
    XDG_DATA_HOME: join(home, '.local/share'),
    PING_CORE_BIN: CORE,
    PING_WORKSPACE: workspace
  }
  const started = Date.now()
  const child = spawn(shell.command[0], shell.command.slice(1), { env, stdio: ['ignore', 'ignore', 'pipe'] })
  let log = ''
  child.stderr.on('data', (chunk) => (log = (log + chunk).slice(-20_000)))
  return { child, started, log: () => log }
}

async function stop(run) {
  for (const pid of tree(run.child.pid).reverse()) {
    try {
      process.kill(pid, 'SIGTERM')
    } catch {}
  }
  await Promise.race([new Promise((done) => run.child.once('exit', done)), sleep(5000)])
  for (const pid of tree(run.child.pid)) {
    try {
      process.kill(pid, 'SIGKILL')
    } catch {}
  }
  await sleep(500)
}

function dropCaches() {
  execFileSync('sync')
  writeFileSync('/proc/sys/vm/drop_caches', '3')
}

/** One launch to the `ready` report. Returns the page's timings relative to the spawn. */
async function startupRun(name, home, workspace, cold) {
  if (cold) dropCaches()
  let resolveReady
  const ready = new Promise((done) => (resolveReady = done))
  onPhase = (phase, data) => {
    if (phase === 'ready') resolveReady({ at: Date.now(), data })
    if (phase === 'error') resolveReady({ error: data.message })
    return { mode: 'startup' }
  }
  const run = launch(name, home, workspace)
  const outcome = await Promise.race([ready, sleep(120_000).then(() => ({ error: 'no ready report in 120s' }))])
  await stop(run)
  if (outcome.error) throw new Error(`${name}: ${outcome.error}\n${run.log()}`)
  const { data } = outcome
  return {
    navigationStartMs: data.timeOrigin - run.started,
    domContentLoadedMs: data.timeOrigin + data.domContentLoaded - run.started,
    firstPaintMs: data.paint.length ? data.timeOrigin + data.paint[0].at - run.started : null,
    readyMs: data.readyAt - run.started,
    userAgent: data.userAgent
  }
}

function windowId() {
  return execFileSync('xdotool', ['search', '--onlyvisible', '--name', '^Ping$']).toString().trim().split('\n')[0]
}

/** Every scenario in one launch, with memory and CPU sampled between phases. */
async function fullRun(name, home, workspace) {
  const result = { phases: {}, memory: {}, cpu: {} }
  let run
  let finished
  const done = new Promise((resolve, reject) => (finished = { resolve, reject }))
  onPhase = async (phase, data) => {
    if (phase === 'error') {
      finished.reject(new Error(`${name}: ${data.message}`))
      return {}
    }
    if (phase === 'ready') {
      await sleep(5000) // let startup work settle before calling it idle
      result.memory.idle = memory(run.child.pid)
      result.cpu.idlePercent = await cpuPercent(run.child.pid, 10_000)
      return { mode: 'full' }
    }
    if (phase === 'typing-ready') {
      const id = windowId()
      try {
        execFileSync('xdotool', ['windowfocus', '--sync', id])
      } catch {}
      await sleep(300)
      // 60 real key presses, 80ms apart: slower than the frame budget, so each is timed alone.
      execFileSync('xdotool', ['type', '--delay', '80', 'abcdefghij'.repeat(6)])
      await sleep(500)
      return {}
    }
    result.phases[phase] = data
    if (phase.startsWith('render-')) {
      await sleep(2000)
      result.memory[`after-${phase}`] = memory(run.child.pid)
    }
    if (phase === 'done') {
      await sleep(2000)
      result.memory.end = memory(run.child.pid)
      result.cpu.endIdlePercent = await cpuPercent(run.child.pid, 5000)
      finished.resolve()
    }
    return {}
  }
  run = launch(name, home, workspace)
  try {
    await Promise.race([done, sleep(600_000).then(() => Promise.reject(new Error('full run timed out')))])
  } catch (error) {
    error.message += `\n${run.log()}`
    throw error
  } finally {
    await stop(run)
  }
  return result
}

// --- Main --------------------------------------------------------------------------------------

const workspace = prepareWorkspace()
if (process.env.BENCH_DEBUG) process.stderr.write(`workspace ${workspace}: ${readdirSync(workspace)}\n`)
const results = { machine: machine(), startedAt: new Date().toISOString(), shells: {} }

function machine() {
  const cpu = readFileSync('/proc/cpuinfo', 'utf8').match(/^model name\s*:\s*(.*)$/m)?.[1]
  const memTotal = readFileSync('/proc/meminfo', 'utf8').match(/^MemTotal:\s+(\d+)/m)?.[1]
  return { cpu, cores: readFileSync('/proc/cpuinfo', 'utf8').match(/^processor/gm).length, memoryGB: Number(memTotal) / 1024 / 1024, kernel: execFileSync('uname', ['-r']).toString().trim() }
}

for (const [name] of shells) {
  const home = join(work, name)
  const entry = (results.shells[name] = { warm: [], cold: [], full: [] })

  // The first launch creates the profile; it is neither cold nor warm, so it is reported apart.
  entry.firstLaunch = await startupRun(name, home, workspace, false)
  for (let i = 0; i < STARTUP_RUNS; i++) {
    entry.warm.push(await startupRun(name, home, workspace, false))
    process.stderr.write(`${name} warm ${i + 1}/${STARTUP_RUNS}: ready ${Math.round(entry.warm.at(-1).readyMs)}ms\n`)
  }
  for (let i = 0; i < COLD_RUNS; i++) {
    entry.cold.push(await startupRun(name, home, workspace, true))
    process.stderr.write(`${name} cold ${i + 1}/${COLD_RUNS}: ready ${Math.round(entry.cold.at(-1).readyMs)}ms\n`)
  }
  for (let i = 0; i < FULL_RUNS; i++) {
    entry.full.push(await fullRun(name, home, workspace))
    process.stderr.write(`${name} full ${i + 1}/${FULL_RUNS} done\n`)
  }
  writeFileSync(OUT, `${JSON.stringify(results, null, 2)}\n`)
}

rmSync(work, { recursive: true, force: true })
process.stderr.write(`results in ${OUT}\n`)
// The app's HTTP client keeps its connections to the collector alive; do not wait on them.
server.closeAllConnections()
server.close()
process.exit(0)
