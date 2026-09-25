import { appendFileSync, mkdirSync, renameSync, statSync } from 'node:fs'
import { join } from 'node:path'
import type { LogEntry } from '../shared/logs'

/** Lines kept in memory for the Logs view. Older ones are still in the file. */
const CAPACITY = 2000

/** The file rolls over to `ping.1.log` past this size, so the two never exceed twice it. */
const MAX_FILE_BYTES = 1024 * 1024

/**
 * A line longer than this is cut. The core's "unparseable line" diagnostic quotes whatever it
 * could not parse, which can be a whole response body.
 */
const MAX_LINE_CHARS = 4000

/**
 * A known value shorter than this is left alone: masking a one-character secret would mask that
 * character everywhere in the log and make it unreadable, for a value that protects nothing.
 */
const MIN_MASKED_LENGTH = 4

const MASK = '***'

const HEADER_NAMES = String.raw`(?:proxy-)?authorization|(?:set-)?cookie|x-api-key`
const JSON_HEADER = new RegExp(String.raw`("(?:${HEADER_NAMES})"\s*:\s*)"(?:[^"\\]|\\.)*"`, 'gi')
const PLAIN_HEADER = new RegExp(String.raw`\b(${HEADER_NAMES})(\s*[:=]\s*)[^\r\n]+`, 'gi')
const URL_PASSWORD = /(\b[a-z][a-z0-9+.-]*:\/\/[^\s/:@]*:)[^\s/@]+@/gi
const QUERY_CREDENTIAL =
  /([?&](?:access_token|refresh_token|id_token|token|api_key|apikey|key|password|client_secret|secret|signature|sig)=)[^&\s#"']+/gi

/**
 * Masks what a log line must never carry: the known secret values, and the credentials that have
 * a recognizable shape (auth and cookie headers, a URL's password, token-like query parameters).
 *
 * This is a safety net, not a licence to log request data: a value that is neither known nor
 * shaped like a credential passes through untouched.
 */
export function redact(text: string, values: readonly string[] = []): string {
  let out = text
  // Longest first, so a secret that contains another is masked whole.
  const known = values.filter((value) => value.length >= MIN_MASKED_LENGTH)
  known.sort((a, b) => b.length - a.length)
  for (const value of known) {
    if (out.includes(value)) {
      out = out.split(value).join(MASK)
    }
  }
  return out
    .replace(JSON_HEADER, `$1"${MASK}"`)
    .replace(PLAIN_HEADER, `$1$2${MASK}`)
    .replace(URL_PASSWORD, `$1${MASK}@`)
    .replace(QUERY_CREDENTIAL, `$1${MASK}`)
}

function truncate(line: string): string {
  if (line.length <= MAX_LINE_CHARS) {
    return line
  }
  return `${line.slice(0, MAX_LINE_CHARS)}… (${line.length - MAX_LINE_CHARS} more characters)`
}

export interface LoggerOptions {
  capacity?: number
  maxFileBytes?: number
  /** Where every line is echoed as `[source] text`. Defaults to the process's stderr. */
  echo?: (line: string) => void
}

/**
 * The shell's diagnostic log: every `[tag]` line the main process used to write straight to
 * stderr, and everything the core writes to its own stderr.
 *
 * A packaged app has no terminal, so a line only on stderr is a line nobody reads. Each one is
 * redacted, echoed to stderr as before, kept in a ring buffer for the Logs view, and appended
 * to `ping.log` in `userData/logs` once {@link attach} names that folder.
 */
export class Logger {
  private entries: LogEntry[] = []
  private seq = 0
  private folder: string | null = null
  private fileBytes = 0
  private sensitive: () => readonly string[] = () => []
  private listeners = new Set<(entry: LogEntry) => void>()
  private readonly capacity: number
  private readonly maxFileBytes: number
  private readonly echo: (line: string) => void

  constructor(options: LoggerOptions = {}) {
    this.capacity = options.capacity ?? CAPACITY
    this.maxFileBytes = options.maxFileBytes ?? MAX_FILE_BYTES
    this.echo = options.echo ?? ((line) => process.stderr.write(line))
  }

  /** The values to mask, read on every line so a secret set a moment ago is covered. */
  maskValues(provider: () => readonly string[]): void {
    this.sensitive = provider
  }

  /**
   * Starts writing to `<folder>/ping.log`. Lines logged before this (the buffer still holds
   * them) are written first, so the file starts where the session did.
   */
  attach(folder: string): void {
    try {
      mkdirSync(folder, { recursive: true })
      this.folder = folder
      this.fileBytes = sizeOf(this.file())
      if (this.entries.length > 0) {
        this.append(this.entries.map(format).join(''))
      }
    } catch (error) {
      this.folder = null
      this.echo(`[log] cannot write logs to ${folder}: ${String(error)}\n`)
    }
  }

  /** The folder the file is written to, or null before {@link attach} (or after it failed). */
  dir(): string | null {
    return this.folder
  }

  /** Logs a message under a tag. A message with several lines becomes one entry per line. */
  write(source: string, message: string): void {
    const lines = message.replace(/\r?\n$/, '').split(/\r?\n/)
    let values: readonly string[] = []
    try {
      values = this.sensitive()
    } catch {
      // A store that cannot answer must not cost the log line; the shape rules still apply.
    }
    for (const line of lines) {
      if (!line.trim()) {
        continue
      }
      const entry: LogEntry = {
        seq: ++this.seq,
        time: Date.now(),
        source,
        text: truncate(redact(line, values))
      }
      this.entries.push(entry)
      if (this.entries.length > this.capacity) {
        this.entries.splice(0, this.entries.length - this.capacity)
      }
      this.echo(`[${source}] ${entry.text}\n`)
      this.append(format(entry))
      for (const listener of this.listeners) {
        listener(entry)
      }
    }
  }

  list(): LogEntry[] {
    return [...this.entries]
  }

  /** Empties the Logs view. The file keeps everything. */
  clear(): void {
    this.entries = []
  }

  onEntry(listener: (entry: LogEntry) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private file(): string {
    return join(this.folder ?? '', 'ping.log')
  }

  private append(text: string): void {
    if (!this.folder) {
      return
    }
    const bytes = Buffer.byteLength(text)
    try {
      if (this.fileBytes > 0 && this.fileBytes + bytes > this.maxFileBytes) {
        renameSync(this.file(), join(this.folder, 'ping.1.log'))
        this.fileBytes = 0
      }
      appendFileSync(this.file(), text)
      this.fileBytes += bytes
    } catch (error) {
      // Echoed rather than logged: logging it would try the same file again.
      this.folder = null
      this.echo(`[log] stopped writing the log file: ${String(error)}\n`)
    }
  }
}

function format(entry: LogEntry): string {
  return `${new Date(entry.time).toISOString()} [${entry.source}] ${entry.text}\n`
}

function sizeOf(path: string): number {
  try {
    return statSync(path).size
  } catch {
    return 0
  }
}

/**
 * Splits a stream into lines for the log. Unlike {@link LineSplitter} it keeps indentation,
 * because a stack trace without it is much harder to read.
 */
export class LogLines {
  private partial = ''

  push(chunk: string): string[] {
    const parts = (this.partial + chunk).split('\n')
    this.partial = parts.pop() ?? ''
    return parts.map((line) => line.replace(/\r$/, '')).filter((line) => line.trim() !== '')
  }

  /** The unterminated tail, for when the stream ends. */
  flush(): string[] {
    const rest = this.partial
    this.partial = ''
    return rest.trim() ? [rest] : []
  }
}

/** The one log the main process writes to. */
export const logger = new Logger()

export function log(source: string, message: string): void {
  logger.write(source, message)
}
