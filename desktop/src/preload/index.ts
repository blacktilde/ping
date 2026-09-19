import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'

/** A core failure in transit. `code` is null when the failure was not a JSON-RPC error. */
export interface CoreRpcError {
  code: number | null
  message: string
}

/**
 * Why calls return a result rather than rejecting: contextBridge does not carry custom
 * Error properties, so a rejection cannot preserve the JSON-RPC code. An envelope does.
 * Renderer code unwraps it through `call` in `lib/core.ts`, which throws a real error.
 */
export type CoreResult<T> = { ok: true; value: T } | { ok: false; error: CoreRpcError }

/**
 * The renderer's entire view of the outside world.
 *
 * Only these functions cross the context bridge, so the UI can never reach Node, the
 * filesystem, or the core process on its own.
 */
const api = {
  request<T = unknown>(method: string, params?: unknown): Promise<CoreResult<T>> {
    return ipcRenderer.invoke('core:request', method, params) as Promise<CoreResult<T>>
  },

  /** Subscribes to server-initiated core messages. Returns an unsubscribe function. */
  onNotification(listener: (notification: { method: string; params: unknown }) => void): () => void {
    const handler = (_event: IpcRendererEvent, notification: { method: string; params: unknown }) =>
      listener(notification)
    ipcRenderer.on('core:notification', handler)
    return () => ipcRenderer.off('core:notification', handler)
  }
}

contextBridge.exposeInMainWorld('ping', api)

export type PingApi = typeof api
