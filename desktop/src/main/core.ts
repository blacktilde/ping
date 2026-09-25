import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { app } from 'electron'
import { LineSplitter } from './lines'
import { log, LogLines } from './log'

/** A server-initiated message: no id, so nothing is waiting on it. */
export interface CoreNotification {
  method: string
  params: unknown
}

/**
 * A JSON-RPC error from the core.
 *
 * The code is kept as a field rather than folded into the message so callers can branch on
 * it: cancellation and a dead network both surface as failures, but only one is a problem.
 */
export class CoreRpcError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'CoreRpcError'
    this.code = code
  }
}

interface PendingCall {
  resolve: (value: unknown) => void
  reject: (reason: Error) => void
  timer: NodeJS.Timeout | null
}

const RESPONSE_TIMEOUT_MS = 60_000

/**
 * Methods whose duration is the user's, not the shell's, so the timeout above must not cut
 * them off: `http.send` runs until its own `timeoutMs` or Cancel, and a run is that timeout
 * once per request in the collection.
 */
const UNBOUNDED_METHODS = new Set(['http.send', 'run.collection'])

const RESTART_BASE_MS = 500
const RESTART_MAX_MS = 10_000

/**
 * Locates the core binary.
 *
 * During development this is the Gradle `installDist` launcher running on the JVM, which
 * rebuilds in seconds. A packaged build ships the GraalVM native image instead. Both speak
 * the same protocol, so only this path differs. `PING_CORE_BIN` overrides for testing one
 * against the other.
 *
 * The two differ again on Windows: `installDist` writes a `.bat` launcher, while
 * `nativeCompile` produces `ping-core.exe`. Naming the development launcher in a packaged
 * build points at a file that was never shipped, and the app starts with no core at all.
 */
function resolveCoreBinary(): string {
  const override = process.env.PING_CORE_BIN
  if (override) {
    return override
  }

  const windows = process.platform === 'win32'

  if (app.isPackaged) {
    // Packaged: the native image electron-builder copied to resources/core.
    const packaged = join(process.resourcesPath, 'core', windows ? 'ping-core.exe' : 'ping-core')
    if (!existsSync(packaged)) {
      throw new Error(`Core binary not found at ${packaged}. The installation is incomplete.`)
    }
    return packaged
  }

  // Development: the JVM launcher, a shell script everywhere but Windows.
  const devPath = join(
    app.getAppPath(),
    '..',
    'core',
    'build',
    'install',
    'ping-core',
    'bin',
    windows ? 'ping-core.bat' : 'ping-core'
  )
  if (!existsSync(devPath)) {
    throw new Error(
      `Core binary not found at ${devPath}. Run './gradlew :core:installDist' first.`
    )
  }
  return devPath
}

/**
 * Owns the core child process and the JSON-RPC conversation with it.
 *
 * Framing is one JSON object per line. Chunks from the pipe do not respect line
 * boundaries, so {@link LineSplitter} holds a partial line until its newline arrives.
 */
export class CoreClient {
  private child: ChildProcessWithoutNullStreams | null = null
  private readonly pending = new Map<number, PendingCall>()
  private readonly lines = new LineSplitter()
  private nextId = 1
  private onNotification: ((notification: CoreNotification) => void) | null = null
  private onStateChange: ((state: 'down' | 'starting' | 'ready') => void) | null = null
  private restarting = false
  private stopped = false
  private restartAttempts = 0

  /** Resolves when the core announces `core.ready`, so callers never race startup. */
  readonly ready: Promise<void>
  private markReady!: () => void
  private failReady!: (reason: Error) => void

  constructor() {
    this.ready = new Promise((resolve, reject) => {
      this.markReady = resolve
      this.failReady = reject
    })
    // A start that fails rejects this before anything awaits it — the window is not even
    // created yet — and an unhandled rejection in the main process is a fatal error dialog
    // instead of an app. Every `await core.ready` still sees the rejection.
    this.ready.catch(() => {})
  }

  start(): void {
    this.stopped = false
    this.spawn()
  }

  private spawn(): void {
    // A missing core must not stop the window from opening: fail `ready`, so every call
    // reports it and the renderer shows the error, rather than rejecting unhandled.
    let binary: string
    try {
      binary = resolveCoreBinary()
    } catch (error) {
      this.fail(error instanceof Error ? error : new Error(String(error)))
      return
    }

    // Node refuses to run a `.bat` or `.cmd` without a shell (CVE-2024-27980), and the
    // development launcher on Windows is exactly that, so it needs one. The quotes survive a
    // path with a space in it: Node hands cmd.exe `/s /c "<command>"`, which strips one outer
    // pair. `windowsHide` keeps a console child from flashing its own window over the app.
    log('core', `starting ${binary}`)
    const batch = process.platform === 'win32' && /\.(bat|cmd)$/i.test(binary)
    const child = spawn(batch ? `"${binary}"` : binary, [], {
      stdio: ['pipe', 'pipe', 'pipe'],
      shell: batch,
      windowsHide: true
    })
    this.child = child
    this.onStateChange?.('starting')

    child.stdout.setEncoding('utf8')
    child.stdout.on('data', (chunk: string) => this.consume(chunk))

    // The core logs diagnostics to stderr precisely so they cannot corrupt the protocol. Pipe
    // chunks ignore line boundaries, so lines are reassembled before they reach the log.
    const diagnostics = new LogLines()
    child.stderr.setEncoding('utf8')
    child.stderr.on('data', (chunk: string) => {
      for (const line of diagnostics.push(chunk)) {
        log('core', line)
      }
    })
    child.stderr.on('end', () => {
      for (const line of diagnostics.flush()) {
        log('core', line)
      }
    })

    child.on('error', (error) => this.fail(new Error(`Core failed to start: ${error.message}`)))
    child.on('exit', (code, signal) => {
      this.fail(new Error(`Core exited (code=${code}, signal=${signal})`))
    })
  }

  notifications(listener: (notification: CoreNotification) => void): void {
    this.onNotification = listener
  }

  /** Lifecycle events for the renderer: `down` after a crash, `ready` once it answers again. */
  stateChanges(listener: (state: 'down' | 'starting' | 'ready') => void): void {
    this.onStateChange = listener
  }

  request(method: string, params?: unknown): Promise<unknown> {
    const child = this.child
    if (!child || child.exitCode !== null) {
      return Promise.reject(new Error('Core is not running'))
    }

    const id = this.nextId++
    return new Promise((resolve, reject) => {
      let timer: NodeJS.Timeout | null = null
      if (!UNBOUNDED_METHODS.has(method)) {
        timer = setTimeout(() => {
          this.pending.delete(id)
          // Tell the core to stop working: without this it finishes a slow handler nobody
          // is waiting for, and its late reply is discarded as a response for an unknown id.
          child.stdin.write(
            `${JSON.stringify({ jsonrpc: '2.0', method: 'http.cancel', params: { requestId: id } })}\n`
          )
          reject(new Error(`The core did not answer ${method} within ${RESPONSE_TIMEOUT_MS}ms`))
        }, RESPONSE_TIMEOUT_MS)
        timer.unref()
      }
      this.pending.set(id, { resolve, reject, timer })
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`)
    })
  }

  /** Closing stdin ends the core's read loop, which is how it learns to shut down. */
  stop(): void {
    this.stopped = true
    this.child?.stdin.end()
    this.child = null
  }

  private consume(chunk: string): void {
    for (const line of this.lines.push(chunk)) {
      this.dispatch(line)
    }
  }

  private dispatch(line: string): void {
    let message: Record<string, any>
    try {
      message = JSON.parse(line)
    } catch {
      log('core', `unparseable line: ${line}`)
      return
    }

    if (message.id === undefined || message.id === null) {
      if (message.method === 'core.ready') {
        log('core', 'ready')
        this.markReady()
        this.restartAttempts = 0
        this.onStateChange?.('ready')
      }
      this.onNotification?.({ method: message.method, params: message.params })
      return
    }

    const call = this.pending.get(message.id)
    if (!call) {
      log('core', `response for unknown id ${message.id}`)
      return
    }
    this.pending.delete(message.id)
    if (call.timer) {
      clearTimeout(call.timer)
    }

    if (message.error) {
      call.reject(new CoreRpcError(message.error.code, message.error.message))
    } else {
      call.resolve(message.result)
    }
  }

  /** A dead core cannot answer anything, so every in-flight call fails now rather than hanging. */
  private fail(error: Error): void {
    if (!this.stopped) {
      log('core', error.message)
    }
    this.failReady(error)
    for (const call of this.pending.values()) {
      if (call.timer) {
        clearTimeout(call.timer)
      }
      call.reject(error)
    }
    this.pending.clear()
    this.child = null

    if (this.stopped || this.restarting) {
      return
    }
    this.scheduleRestart()
  }

  /**
   * Brings the core back after a crash. The native binary is the whole data layer, so a
   * single crash must not brick the session until relaunch. `ready` stays resolved from
   * the first successful start — the renderer re-syncs from the `core:state` events
   * instead of re-awaiting a promise that can only settle once.
   */
  private scheduleRestart(): void {
    this.restarting = true
    this.onStateChange?.('down')
    const delay = Math.min(RESTART_BASE_MS * 2 ** this.restartAttempts, RESTART_MAX_MS)
    this.restartAttempts += 1
    setTimeout(() => {
      this.restarting = false
      if (this.stopped) {
        return
      }
      log('core', `restarting (attempt ${this.restartAttempts})`)
      this.spawn()
    }, delay).unref()
  }
}
