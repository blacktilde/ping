/**
 * `window.ping` on Tauri: the same surface `src/preload/index.ts` exposes on Electron, so the
 * renderer runs unchanged under either shell.
 *
 * Every call is a Tauri command implemented in `src-tauri/src/lib.rs`, where the checks live; this
 * file only adapts shapes. Anything not ported yet (phase 30, docs/PLAN.md) is a stub below that
 * either returns an empty view or fails loudly. None of them pretends to have stored something.
 */
import { invoke } from '@tauri-apps/api/core'
import { listen, type EventCallback } from '@tauri-apps/api/event'
import type { CoreResult, PingApi } from '../preload/index'
import { DEFAULT_NETWORK } from '../shared/network'
import type { UpdateState } from '../shared/updates'

/** `listen` resolves asynchronously; the renderer expects a synchronous unsubscribe. */
function subscribe<T>(event: string, handler: EventCallback<T>): () => void {
  let off: (() => void) | null = null
  let cancelled = false
  void listen<T>(event, handler).then((unlisten) => {
    if (cancelled) {
      unlisten()
    } else {
      off = unlisten
    }
  })
  return () => {
    cancelled = true
    off?.()
  }
}

function notPorted(what: string): Promise<never> {
  return Promise.reject(new Error(`${what} is not available in the Tauri shell yet`))
}

const NO_UPDATES: UpdateState = { status: 'idle', enabled: false, version: null, progress: null, error: null }

const api: PingApi = {
  request<T = unknown>(method: string, params?: unknown): Promise<CoreResult<T>> {
    return invoke<CoreResult<T>>('core_request', { method, params: params ?? null })
  },
  workspace: () => invoke('workspace_current'),
  chooseWorkspace: () => invoke('workspace_choose'),
  openInFileManager: () => notPorted('Opening the file manager'),
  saveResponse: () => notPorted('Saving a response'),
  importCollection: () => notPorted('Import'),
  exportCollections: () => notPorted('Export'),
  pickFile: () => notPorted('Choosing a file'),
  onStoreChanged: () => () => {},

  secrets: {
    list: () => Promise.resolve([]),
    set: () => notPorted('Saving a secret'),
    remove: () => notPorted('Removing a secret')
  },
  network: {
    get: () => Promise.resolve(DEFAULT_NETWORK),
    set: () => notPorted('Network settings'),
    addCert: () => notPorted('Client certificates'),
    removeCert: () => notPorted('Client certificates')
  },
  runtime: {
    list: () => invoke('runtime_list'),
    clear: () => invoke('runtime_clear')
  },
  cookies: {
    list: (collection, environment) => invoke('cookies_list', { collection, environment }),
    clear: (collection, environment, domain, name) =>
      invoke('cookies_clear', { collection, environment, domain: domain ?? null, name: name ?? null })
  },
  updates: {
    state: () => Promise.resolve(NO_UPDATES),
    check: () => Promise.resolve(NO_UPDATES),
    download: () => notPorted('Updates'),
    install: () => notPorted('Updates'),
    onState: () => () => {}
  },

  onNotification: (listener) =>
    subscribe<{ method: string; params: unknown }>('core:notification', (event) => listener(event.payload)),
  onCoreState: (listener) =>
    subscribe<'down' | 'starting' | 'ready'>('core:state', (event) => listener(event.payload)),

  history: {
    list: () => Promise.resolve([]),
    add: () => Promise.resolve([]),
    clear: () => Promise.resolve()
  },
  logs: {
    list: () => Promise.resolve([]),
    clear: () => Promise.resolve(),
    openFolder: () => notPorted('The log folder'),
    onEntry: () => () => {}
  },

  // Quit protection is not ported: the window closes without asking about unsaved tabs.
  onCloseRequest: () => () => {},
  confirmClose: () => Promise.resolve()
}

window.ping = api
