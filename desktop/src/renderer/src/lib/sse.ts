/**
 * Server-sent events, parsed incrementally.
 *
 * The core delivers a feed's text in chunks that end wherever a network read did, so an event can
 * arrive in pieces and a CRLF can be split between them. `SseParser` keeps the unfinished part and
 * returns only whole events, as the WHATWG event-stream format defines them: fields `event`,
 * `data`, `id` and `retry`, `:` comments ignored, a blank line dispatching, and a record with no
 * `data` dropped.
 */

export interface SseEvent {
  /** 1, 2, 3, … in arrival order. */
  index: number
  event?: string
  id?: string
  retry?: number
  data: string
  /** Milliseconds since the stream's head arrived, from the chunk the event completed in. */
  atMs: number
}

export class SseParser {
  private buffer = ''
  private data: string[] = []
  private event: string | undefined
  private id: string | undefined
  private retry: number | undefined
  private count = 0

  /** Feeds a chunk and returns the events it completed. */
  push(text: string, atMs: number): SseEvent[] {
    this.buffer += text
    const events: SseEvent[] = []
    let start = 0
    while (true) {
      const end = this.lineEnd(start)
      if (end === null) {
        break
      }
      const line = this.buffer.slice(start, end.at)
      start = end.next
      const event = this.line(line, atMs)
      if (event) {
        events.push(event)
      }
    }
    this.buffer = this.buffer.slice(start)
    return events
  }

  /** The end of the line starting at `from`, or null while it is unfinished. */
  private lineEnd(from: number): { at: number; next: number } | null {
    for (let i = from; i < this.buffer.length; i++) {
      const c = this.buffer[i]
      if (c === '\n') {
        return { at: i, next: i + 1 }
      }
      if (c === '\r') {
        // A CR at the very end may be the first half of a CRLF split across chunks: wait.
        if (i + 1 >= this.buffer.length) {
          return null
        }
        return { at: i, next: this.buffer[i + 1] === '\n' ? i + 2 : i + 1 }
      }
    }
    return null
  }

  private line(line: string, atMs: number): SseEvent | null {
    if (line === '') {
      return this.dispatch(atMs)
    }
    if (line.startsWith(':')) {
      return null
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    switch (field) {
      case 'event':
        this.event = value
        break
      case 'data':
        this.data.push(value)
        break
      case 'id':
        if (!value.includes('\0')) {
          this.id = value
        }
        break
      case 'retry':
        if (/^\d+$/.test(value)) {
          this.retry = Number(value)
        }
        break
    }
    return null
  }

  private dispatch(atMs: number): SseEvent | null {
    const data = this.data
    const event = this.event
    const retry = this.retry
    this.data = []
    this.event = undefined
    this.retry = undefined
    if (data.length === 0) {
      return null
    }
    // `id` carries over to later events, like the spec's last event ID, so it is not reset here.
    return { index: ++this.count, event, id: this.id, retry, data: data.join('\n'), atMs }
  }
}

/** Every event in a finished body, for a response that never went through the live path. */
export function parseSse(text: string): SseEvent[] {
  return new SseParser().push(text.endsWith('\n') || text.endsWith('\r') ? text : `${text}\n\n`, 0)
}

export function isEventStream(contentType: string | null | undefined): boolean {
  return (contentType ?? '').toLowerCase().split(';')[0].trim() === 'text/event-stream'
}
