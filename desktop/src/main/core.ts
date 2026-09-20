import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { app } from 'electron'

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

/** `http.send` runs until its own `timeoutMs` or Cancel, so the shell must not cut it off. */
const UNBOUNDED_METHODS = new Set(['http.send'])

/**
 * Locates the core binary.
 *
 * During development this is the Gradle `installDist` launcher running on the JVM, which
 * rebuilds in seconds. A packaged build ships the GraalVM native image instead. Both speak
 * the same protocol, so only this path differs. `PING_CORE_BIN` overrides for testing one
 * against the other.
 */
function resolveCoreBinary(): string {
  const override = process.env.PING_CORE_BIN
  if (override) {
    return override
  }

  const executable = process.platform === 'win32' ? 'ping-core.bat' : 'ping-core'

  if (app.isPackaged) {
    return join(process.resourcesPath, 'core', executable)
  }

  const devPath = join(app.getAppPath(), '..', 'core', 'build', 'install', 'ping-core', 'bin', executable)
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
 * boundaries, so partial lines are held in {@link buffer} until a newline arrives.
 */
export class CoreClient {
  private child: ChildProcessWithoutNullStreams | null = null
  private readonly pending = new Map<number, PendingCall>()
  private buffer = ''
  private nextId = 1
  private onNotification: ((notification: CoreNotification) => void) | null = null

  /** Resolves when the core announces `core.ready`, so callers never race startup. */
  readonly ready: Promise<void>
  private markReady!: () => void
  private failReady!: (reason: Error) => void

  constructor() {
    this.ready = new Promise((resolve, reject) => {
      this.markReady = resolve
      this.failReady = reject
    })
  }

  start(): void {
    // A missing core must not stop the window from opening: fail `ready`, so every call
    // reports it and the renderer shows the error, rather than rejecting unhandled.
    let binary: string
    try {
      binary = resolveCoreBinary()
    } catch (error) {
      this.fail(error instanceof Error ? error : new Error(String(error)))
      return
    }

    const child = spawn(binary, [], { stdio: ['pipe', 'pipe', 'pipe'] })
    this.child = child

    child.stdout.setEncoding('utf8')
    child.stdout.on('data', (chunk: string) => this.consume(chunk))

    // The core logs diagnostics to stderr precisely so they cannot corrupt the protocol.
    child.stderr.setEncoding('utf8')
    child.stderr.on('data', (chunk: string) => process.stderr.write(`[core] ${chunk}`))

    child.on('error', (error) => this.fail(new Error(`Core failed to start: ${error.message}`)))
    child.on('exit', (code, signal) =>
      this.fail(new Error(`Core exited (code=${code}, signal=${signal})`))
    )
  }

  notifications(listener: (notification: CoreNotification) => void): void {
    this.onNotification = listener
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
    this.child?.stdin.end()
    this.child = null
  }

  private consume(chunk: string): void {
    this.buffer += chunk

    let newline = this.buffer.indexOf('\n')
    while (newline !== -1) {
      const line = this.buffer.slice(0, newline).trim()
      this.buffer = this.buffer.slice(newline + 1)
      if (line) {
        this.dispatch(line)
      }
      newline = this.buffer.indexOf('\n')
    }
  }

  private dispatch(line: string): void {
    let message: Record<string, any>
    try {
      message = JSON.parse(line)
    } catch {
      process.stderr.write(`[core] unparseable line: ${line}\n`)
      return
    }

    if (message.id === undefined || message.id === null) {
      if (message.method === 'core.ready') {
        this.markReady()
      }
      this.onNotification?.({ method: message.method, params: message.params })
      return
    }

    const call = this.pending.get(message.id)
    if (!call) {
      process.stderr.write(`[core] response for unknown id ${message.id}\n`)
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
    this.failReady(error)
    for (const call of this.pending.values()) {
      if (call.timer) {
        clearTimeout(call.timer)
      }
      call.reject(error)
    }
    this.pending.clear()
    this.child = null
  }
}
