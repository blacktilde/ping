import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import type { HistoryEntry } from '../shared/history'
import type { ImportReport } from '../shared/import'
import type { UpdateState } from '../shared/updates'

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

  /**
   * Saves a response body to a file the user picks. Pass exactly one of `text` (textual
   * bodies) or `base64` (binary bodies); the chosen path is returned, or null on cancel.
   * The renderer never picks the destination itself.
   */
  saveResponse(payload: {
    suggestedName: string
    text?: string
    base64?: string
  }): Promise<string | null> {
    return ipcRenderer.invoke('response:save', payload) as Promise<string | null>
  },

  /**
   * Imports a Postman or Insomnia export, or an OpenAPI document. The shell shows the file picker, reads the file and
   * stores any credentials it contains; the renderer only learns what was created. Resolves
   * with `value: null` when the dialog is dismissed.
   */
  importCollection(): Promise<CoreResult<ImportReport | null>> {
    return ipcRenderer.invoke('import:collection') as Promise<CoreResult<ImportReport | null>>
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

  /**
   * The updater, owned by the shell. The renderer reads its state, asks for the next step,
   * and subscribes to changes; it can never download or install on its own.
   */
  updates: {
    state(): Promise<UpdateState> {
      return ipcRenderer.invoke('updates:state') as Promise<UpdateState>
    },
    check(): Promise<UpdateState> {
      return ipcRenderer.invoke('updates:check') as Promise<UpdateState>
    },
    download(): Promise<UpdateState> {
      return ipcRenderer.invoke('updates:download') as Promise<UpdateState>
    },
    install(): Promise<UpdateState> {
      return ipcRenderer.invoke('updates:install') as Promise<UpdateState>
    },
    onState(listener: (state: UpdateState) => void): () => void {
      const handler = (_event: IpcRendererEvent, state: UpdateState): void => listener(state)
      ipcRenderer.on('updates:state', handler)
      return () => ipcRenderer.off('updates:state', handler)
    }
  },

  /** Subscribes to server-initiated core messages. Returns an unsubscribe function. */
  onNotification(listener: (notification: { method: string; params: unknown }) => void): () => void {
    const handler = (_event: IpcRendererEvent, notification: { method: string; params: unknown }) =>
      listener(notification)
    ipcRenderer.on('core:notification', handler)
    return () => ipcRenderer.off('core:notification', handler)
  },

  /** Core process lifecycle: `down` after a crash, `starting` while it respawns, `ready` after. */
  onCoreState(listener: (state: 'down' | 'starting' | 'ready') => void): () => void {
    const handler = (_event: IpcRendererEvent, state: 'down' | 'starting' | 'ready'): void =>
      listener(state)
    ipcRenderer.on('core:state', handler)
    return () => ipcRenderer.off('core:state', handler)
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
  },

  /**
   * Quit protection. The window close is intercepted in main, which asks the renderer —
   * the only place that knows about unsaved tabs — to confirm; `confirmClose` then lets
   * the close proceed.
   */
  onCloseRequest(listener: () => void): () => void {
    const handler = (): void => listener()
    ipcRenderer.on('app:close-request', handler)
    return () => ipcRenderer.off('app:close-request', handler)
  },
  confirmClose(): Promise<void> {
    return ipcRenderer.invoke('app:confirm-close') as Promise<void>
  }
}

contextBridge.exposeInMainWorld('ping', api)

export type PingApi = typeof api
