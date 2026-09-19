import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import type { HistoryEntry } from '../shared/history'

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

  /** The folder the shell has open, or null. The renderer never chooses it directly. */
  workspace(): Promise<{ root: string } | null> {
    return ipcRenderer.invoke('workspace:current') as Promise<{ root: string } | null>
  },

  /** Opens the folder picker. Returns the current folder when the dialog is dismissed. */
  chooseWorkspace(): Promise<{ root: string } | null> {
    return ipcRenderer.invoke('workspace:choose') as Promise<{ root: string } | null>
  },

  /** Opens a collection or folder, named relative to the workspace, in the OS file manager. */
  openInFileManager(path: string): Promise<void> {
    return ipcRenderer.invoke('workspace:open', path) as Promise<void>
  },

  /** Fires when the open folder changes on disk, so the tree can be rescanned. */
  onStoreChanged(listener: () => void): () => void {
    const handler = (): void => listener()
    ipcRenderer.on('store:changed', handler)
    return () => ipcRenderer.off('store:changed', handler)
  },

  /**
   * Secret values, kept in the main process. The renderer can list names and write values,
   * but can never read one back: they are merged into requests on the way to the core.
   */
  secrets: {
    list(): Promise<string[]> {
      return ipcRenderer.invoke('secrets:list') as Promise<string[]>
    },
    set(name: string, value: string): Promise<void> {
      return ipcRenderer.invoke('secrets:set', name, value) as Promise<void>
    },
    remove(name: string): Promise<void> {
      return ipcRenderer.invoke('secrets:delete', name) as Promise<void>
    }
  },

  /** Subscribes to server-initiated core messages. Returns an unsubscribe function. */
  onNotification(listener: (notification: { method: string; params: unknown }) => void): () => void {
    const handler = (_event: IpcRendererEvent, notification: { method: string; params: unknown }) =>
      listener(notification)
    ipcRenderer.on('core:notification', handler)
    return () => ipcRenderer.off('core:notification', handler)
  },

  /**
   * Executed requests, kept by the shell in `userData` so they survive a restart and
   * follow the user rather than the open folder. Recording is opt-in from the renderer.
   */
  history: {
    list(): Promise<HistoryEntry[]> {
      return ipcRenderer.invoke('history:list') as Promise<HistoryEntry[]>
    },
    add(entry: HistoryEntry): Promise<HistoryEntry[]> {
      return ipcRenderer.invoke('history:add', entry) as Promise<HistoryEntry[]>
    },
    clear(): Promise<void> {
      return ipcRenderer.invoke('history:clear') as Promise<void>
    }
  }
}

contextBridge.exposeInMainWorld('ping', api)

export type PingApi = typeof api
