/**
 * JSON formatting for response bodies. Small bodies are formatted in place; large ones go to
 * a worker, so a multi-megabyte response does not freeze the window while it is re-indented.
 */
import { prettyJson } from './response'

/** Below this a worker round trip costs more than formatting in place. */
export const FORMAT_OFF_THREAD_FROM = 256 * 1024

interface Waiting {
  text: string
  resolve: (formatted: string | null) => void
}

// undefined: not started yet; null: unavailable, so everything is formatted in place.
let worker: Worker | null | undefined
let nextId = 1
const waiting = new Map<number, Waiting>()

function formatter(): Worker | null {
  if (worker !== undefined) {
    return worker
  }
  try {
    worker = new Worker(new URL('./pretty.worker.ts', import.meta.url), { type: 'module' })
  } catch {
    worker = null
    return worker
  }
  worker.onmessage = (event: MessageEvent<{ id: number; text: string | null }>) => {
    const entry = waiting.get(event.data.id)
    waiting.delete(event.data.id)
    entry?.resolve(event.data.text)
  }
  // A worker that cannot load must not leave a response unformatted forever: finish what it
  // was given here, and format in place from now on.
  worker.onerror = () => {
    worker?.terminate()
    worker = null
    for (const entry of waiting.values()) {
      entry.resolve(prettyJson(entry.text))
    }
    waiting.clear()
  }
  return worker
}

/** The body re-indented, or null when it is not JSON. */
export function formatJson(text: string): Promise<string | null> {
  const target = text.length >= FORMAT_OFF_THREAD_FROM ? formatter() : null
  if (!target) {
    return Promise.resolve(prettyJson(text))
  }
  return new Promise((resolve) => {
    const id = nextId++
    waiting.set(id, { text, resolve })
    target.postMessage({ id, text })
  })
}
