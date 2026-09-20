import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { app, safeStorage } from 'electron'
import { writeFileSyncAtomic } from './atomic'
import {
  DEFAULT_NETWORK,
  type NetworkSettings,
  type NetworkUpdate,
  type ProxyMode
} from '../shared/network'

/** The environment variables the core reads in `system` mode; nothing else is handed to it. */
const PROXY_VARIABLES = [
  'HTTP_PROXY',
  'HTTPS_PROXY',
  'ALL_PROXY',
  'NO_PROXY',
  'http_proxy',
  'https_proxy',
  'all_proxy',
  'no_proxy'
]

interface Persisted {
  proxy: { mode: ProxyMode; url: string; username: string; bypass: string; password?: string }
}

/**
 * App-global network settings, kept in `userData/network.json`.
 *
 * Not collection data on purpose: a shared collection must not be able to route the user's
 * traffic, and its credentials, through a proxy of its choosing. The core receives these only
 * from `withNetwork` in the main process.
 *
 * The password is encrypted with `safeStorage` and kept out of `SecretStore`: that store's
 * names are offered to the renderer and its values are merged into request variables, which
 * would make the proxy password reachable as `{{name}}` from any request. With no OS keyring it
 * stays in memory for the session rather than being written in the clear.
 */
export class NetworkStore {
  private stored: Persisted = { proxy: { mode: 'none', url: '', username: '', bypass: '' } }
  private password = ''

  load(): void {
    if (!existsSync(this.file())) {
      return
    }
    try {
      const parsed = JSON.parse(readFileSync(this.file(), 'utf8')) as unknown
      const proxy = (parsed as { proxy?: Record<string, unknown> } | null)?.proxy
      if (!proxy || typeof proxy !== 'object') {
        return
      }
      this.stored.proxy = {
        mode: isMode(proxy.mode) ? proxy.mode : 'none',
        url: text(proxy.url),
        username: text(proxy.username),
        bypass: text(proxy.bypass)
      }
      if (typeof proxy.password === 'string' && proxy.password && safeStorage.isEncryptionAvailable()) {
        this.password = safeStorage.decryptString(Buffer.from(proxy.password, 'base64'))
      }
    } catch (error) {
      process.stderr.write(`[network] could not read the network settings: ${String(error)}\n`)
    }
  }

  /** The settings without the password: only whether one is set. */
  get(): NetworkSettings {
    return {
      proxy: { ...DEFAULT_NETWORK.proxy, ...this.stored.proxy, hasPassword: this.password !== '' }
    }
  }

  set(update: unknown): NetworkSettings {
    const proxy = validate(update)
    this.stored.proxy = {
      mode: proxy.mode,
      url: proxy.url,
      username: proxy.username,
      bypass: proxy.bypass
    }
    const password = (update as NetworkUpdate).password
    if (typeof password === 'string') {
      this.password = password
    }
    this.persist()
    return this.get()
  }

  /**
   * What the core is told for a call, or undefined to go direct. The password appears here and
   * nowhere else; `system` mode carries only the proxy variables of the environment Ping runs in.
   */
  forCore(): Record<string, unknown> | undefined {
    const { mode, url, username, bypass } = this.stored.proxy
    if (mode === 'system') {
      const env: Record<string, string> = {}
      for (const name of PROXY_VARIABLES) {
        const value = process.env[name]
        if (value) {
          env[name] = value
        }
      }
      return { proxy: { mode, env } }
    }
    if (mode === 'manual') {
      return {
        proxy: {
          mode,
          url,
          username: username || undefined,
          password: this.password || undefined,
          bypass: bypass.split(/[,\s]+/).filter(Boolean)
        }
      }
    }
    return undefined
  }

  private persist(): void {
    try {
      const proxy: Persisted['proxy'] = { ...this.stored.proxy }
      if (this.password && safeStorage.isEncryptionAvailable()) {
        proxy.password = safeStorage.encryptString(this.password).toString('base64')
      } else if (this.password) {
        process.stderr.write('[network] no OS keyring; the proxy password is not persisted this session\n')
      }
      writeFileSyncAtomic(this.file(), JSON.stringify({ proxy }))
    } catch (error) {
      process.stderr.write(`[network] could not write the network settings: ${String(error)}\n`)
    }
  }

  private file(): string {
    return join(app.getPath('userData'), 'network.json')
  }
}

function isMode(value: unknown): value is ProxyMode {
  return value === 'none' || value === 'system' || value === 'manual'
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/**
 * Rejects what the core would reject, so the dialog can say so at save time rather than on the
 * next send. The core checks again; this is the friendly one.
 */
function validate(update: unknown): NetworkUpdate['proxy'] {
  const proxy = (update as NetworkUpdate | null)?.proxy
  if (!proxy || typeof proxy !== 'object' || !isMode(proxy.mode)) {
    throw new Error('network:set requires a proxy mode of none, system or manual')
  }
  const result = {
    mode: proxy.mode,
    url: text(proxy.url).trim(),
    username: text(proxy.username).trim(),
    bypass: text(proxy.bypass).trim()
  }
  if (result.url.length > 2048 || result.username.length > 256 || result.bypass.length > 4096) {
    throw new Error('A proxy setting is too long')
  }
  if (result.mode === 'manual') {
    if (!result.url) {
      throw new Error('Enter the proxy address, for example proxy.example.com:8080')
    }
    if (/^socks/i.test(result.url)) {
      throw new Error('SOCKS proxies are not supported; use an HTTP proxy')
    }
    if (/^https:/i.test(result.url)) {
      throw new Error('An https:// proxy address is not supported; use http:// (HTTPS requests are still tunnelled)')
    }
  }
  return result
}
