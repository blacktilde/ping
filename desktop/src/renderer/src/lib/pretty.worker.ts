/**
 * Pretty-prints JSON off the renderer's main thread. Parsing and re-serialising a body of
 * several megabytes takes long enough to freeze the window, so large bodies come here.
 * Self-contained on purpose: the worker loads nothing but this file.
 */
interface FormatRequest {
  id: number
  text: string
}

// The DOM lib types `self` as a Window, whose postMessage needs a target origin; in a
// dedicated worker it is a DedicatedWorkerGlobalScope, whose postMessage takes the message.
const scope = self as unknown as {
  onmessage: ((event: MessageEvent<FormatRequest>) => void) | null
  postMessage: (message: { id: number; text: string | null }) => void
}

scope.onmessage = (event) => {
  const { id, text } = event.data
  let formatted: string | null
  try {
    formatted = JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    formatted = null
  }
  scope.postMessage({ id, text: formatted })
}
