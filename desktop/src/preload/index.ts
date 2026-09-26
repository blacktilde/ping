import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import type { HistoryEntry } from '../shared/history'
import type { CookieView } from '../shared/cookies'
import type { ExportReport } from '../shared/export'
import type { ImportReport } from '../shared/import'
import type { LogEntry } from '../shared/logs'
import type { ClientCertRequest, NetworkSettings, NetworkUpdate } from '../shared/network'
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

  /**
   * Exports a collection as a Postman v2.1 file. `path` is the collection relative to the open
   * folder; the shell shows the save dialog and writes the file, so the document never comes
   * back here. Resolves with `value: null` when the dialog is dismissed.
   */
  exportCollection(path: string): Promise<CoreResult<ExportReport | null>> {
    return ipcRenderer.invoke('export:collection', path) as Promise<CoreResult<ExportReport | null>>
  },

  /**
   * Opens a file dialog for an upload. `stored` is what the request should keep: relative to the
   * collection when the file is inside it, otherwise absolute (and readable this session only).
   * Resolves with null when the dialog is dismissed.
   */
  pickFile(collection: string): Promise<{ stored: string; name: string; size: number } | null> {
    return ipcRenderer.invoke('file:pick', collection) as Promise<{
      stored: string
      name: string
      size: number
    } | null>
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
   * The user's proxy settings, app-global and kept by the shell. The password is write-only: `get`
   * says whether one is set, and only the main process ever reads it, when it adds the proxy to a
   * call. A collection cannot set any of this.
   */
  network: {
    get(): Promise<NetworkSettings> {
      return ipcRenderer.invoke('network:get') as Promise<NetworkSettings>
    },
    set(update: NetworkUpdate): Promise<NetworkSettings> {
      return ipcRenderer.invoke('network:set', update) as Promise<NetworkSettings>
    },
    /** Opens file dialogs in the main process; the renderer never supplies a path. */
    addCert(request: ClientCertRequest): Promise<NetworkSettings> {
      return ipcRenderer.invoke('network:addCert', request) as Promise<NetworkSettings>
    },
    removeCert(id: string): Promise<NetworkSettings> {
      return ipcRenderer.invoke('network:removeCert', id) as Promise<NetworkSettings>
    }
  },

  /**
   * Values captured from responses, kept in the main process for the session. The renderer
   * can list their names and clear them, but never read a value: they are merged into requests
   * on the way to the core, like secrets.
   */
  runtime: {
    list(): Promise<string[]> {
      return ipcRenderer.invoke('runtime:list') as Promise<string[]>
    },
    clear(): Promise<void> {
      return ipcRenderer.invoke('runtime:clear') as Promise<void>
    }
  },

  /**
   * The cookie jar for a collection and environment. The jar lives in the core and the shell
   * names the scope, so the renderer can list what is stored and clear it. A cookie's value is a
   * session credential and is never returned.
   */
  cookies: {
    list(collection: string, environment: string): Promise<CookieView[]> {
      return ipcRenderer.invoke('cookies:list', collection, environment) as Promise<CookieView[]>
    },
    clear(collection: string, environment: string, domain?: string, name?: string): Promise<number> {
      return ipcRenderer.invoke('cookies:clear', collection, environment, domain, name) as Promise<number>
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
   * The shell's diagnostic log, already redacted in the main process. The renderer reads it and
   * can empty the view; the file on disk is the shell's, opened in the OS file manager on request.
   */
  logs: {
    list(): Promise<LogEntry[]> {
      return ipcRenderer.invoke('logs:list') as Promise<LogEntry[]>
    },
    clear(): Promise<void> {
      return ipcRenderer.invoke('logs:clear') as Promise<void>
    },
    openFolder(): Promise<void> {
      return ipcRenderer.invoke('logs:openFolder') as Promise<void>
    },
    onEntry(listener: (entry: LogEntry) => void): () => void {
      const handler = (_event: IpcRendererEvent, entry: LogEntry): void => listener(entry)
      ipcRenderer.on('logs:entry', handler)
      return () => ipcRenderer.off('logs:entry', handler)
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
