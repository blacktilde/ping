/**
 * The user's network settings, as the renderer may see them.
 *
 * They are app-global and live only in the shell (`userData/network.json`): a proxy sees every
 * request and its credentials, so a collection, which may come from someone else, must never
 * be able to choose one. The password is write-only: it is stored encrypted by the main process
 * and never returned, only whether one is set.
 */
export type ProxyMode = 'none' | 'system' | 'manual'

export interface ProxySettings {
  mode: ProxyMode
  /** manual: `host:port` or `http://host:port`. */
  url: string
  username: string
  /** manual: hosts that skip the proxy, comma or space separated (`NO_PROXY` form). */
  bypass: string
  /** True when a password is stored. Its value is never sent to the renderer. */
  hasPassword: boolean
}

export interface NetworkSettings {
  proxy: ProxySettings
}

/** What the dialog saves. `password`: undefined keeps the stored one, '' removes it. */
export interface NetworkUpdate {
  proxy: Omit<ProxySettings, 'hasPassword'>
  password?: string
}

export const DEFAULT_NETWORK: NetworkSettings = {
  proxy: { mode: 'none', url: '', username: '', bypass: '', hasPassword: false }
}
