import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'

/**
 * The renderer's entire view of the outside world.
 *
 * Only these functions cross the context bridge, so the UI can never reach Node, the
 * filesystem, or the core process on its own.
 */
const api = {
  request<T = unknown>(method: string, params?: unknown): Promise<T> {
    return ipcRenderer.invoke('core:request', method, params) as Promise<T>
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
