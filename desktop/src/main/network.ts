import { existsSync, readFileSync } from 'node:fs'
import { basename, isAbsolute, join } from 'node:path'
import { app, safeStorage } from 'electron'
import { writeFileSyncAtomic } from './atomic'
import {
  DEFAULT_NETWORK,
  type ClientCertRequest,
  type NetworkSettings,
  type NetworkUpdate,
  type ProxyMode
} from '../shared/network'
import { log } from './log'

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

interface StoredCert {
  id: string
  host: string
  type: 'pkcs12' | 'pem'
  /** Absolute paths the user chose in a dialog. Not secret; the passphrase is. */
  cert: string
  key?: string
  /** base64 of the safeStorage ciphertext. */
  passphrase?: string
}

interface Persisted {
  proxy: { mode: ProxyMode; url: string; username: string; bypass: string; password?: string }
  certs: StoredCert[]
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
  private stored: Persisted = { proxy: { mode: 'none', url: '', username: '', bypass: '' }, certs: [] }
  private password = ''
  /** Certificate passphrases by id: decrypted on load, or memory-only when there is no keyring. */
  private passphrases = new Map<string, string>()

  load(): void {
    if (!existsSync(this.file())) {
      return
    }
    try {
      const parsed = JSON.parse(readFileSync(this.file(), 'utf8')) as unknown
      const root = parsed as { proxy?: Record<string, unknown>; certs?: unknown } | null
      this.loadCerts(root?.certs)
      const proxy = root?.proxy
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
      log('network', `could not read the network settings: ${String(error)}`)
    }
  }

  private loadCerts(value: unknown): void {
    if (!Array.isArray(value)) {
      return
    }
    for (const entry of value) {
      const item = entry as Record<string, unknown> | null
      if (
        !item ||
        typeof item.id !== 'string' ||
        typeof item.host !== 'string' ||
        (item.type !== 'pkcs12' && item.type !== 'pem') ||
        typeof item.cert !== 'string'
      ) {
        continue
      }
      const cert: StoredCert = {
        id: item.id,
        host: item.host,
        type: item.type,
        cert: item.cert,
        key: typeof item.key === 'string' ? item.key : undefined
      }
      if (typeof item.passphrase === 'string' && item.passphrase && safeStorage.isEncryptionAvailable()) {
        try {
          this.passphrases.set(cert.id, safeStorage.decryptString(Buffer.from(item.passphrase, 'base64')))
        } catch (error) {
          log('network', `could not read a certificate passphrase: ${String(error)}`)
        }
      }
      this.stored.certs.push(cert)
    }
  }

  /** The settings without any secret: only whether a password or passphrase is set. */
  get(): NetworkSettings {
    return {
      proxy: { ...DEFAULT_NETWORK.proxy, ...this.stored.proxy, hasPassword: this.password !== '' },
      // Names only: a path tells a compromised renderer where the user keeps their keys.
      certs: this.stored.certs.map((cert) => ({
        id: cert.id,
        host: cert.host,
        type: cert.type,
        files: [cert.cert, cert.key].filter((path): path is string => !!path).map((path) => basename(path)),
        hasPassphrase: this.passphrases.has(cert.id)
      }))
    }
  }

  /**
   * Adds a certificate whose files a dialog chose. The renderer never supplies a path: `files`
   * comes from the main process's own file dialog.
   */
  addCert(request: unknown, files: { cert: string; key?: string }): NetworkSettings {
    const { host, type, passphrase } = validateCert(request)
    if (!isAbsolute(files.cert) || (files.key !== undefined && !isAbsolute(files.key))) {
      throw new Error('A certificate must be chosen with the file dialog')
    }
    const id = `cert-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
    this.stored.certs.push({ id, host, type, cert: files.cert, key: files.key })
    if (passphrase) {
      this.passphrases.set(id, passphrase)
    }
    this.persist()
    return this.get()
  }

  /** Rejects a bad host or format up front, so the user is not asked for files first. */
  checkCert(request: unknown): void {
    validateCert(request)
  }

  removeCert(id: unknown): NetworkSettings {
    if (typeof id !== 'string') {
      throw new Error('network:removeCert requires an id')
    }
    this.stored.certs = this.stored.certs.filter((cert) => cert.id !== id)
    this.passphrases.delete(id)
    this.persist()
    return this.get()
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

  /** The proxy password and certificate passphrases, for the log to mask. Never sent anywhere. */
  sensitiveValues(): string[] {
    return [this.password, ...this.passphrases.values()].filter(Boolean)
  }

  /**
   * What the core is told for a call, or undefined to go direct. The password appears here and
   * nowhere else; `system` mode carries only the proxy variables of the environment Ping runs in.
   */
  forCore(): Record<string, unknown> | undefined {
    const network: Record<string, unknown> = {}
    const proxy = this.proxyForCore()
    if (proxy) {
      network.proxy = proxy
    }
    if (this.stored.certs.length > 0) {
      network.clientCerts = this.stored.certs.map((cert) => ({
        host: cert.host,
        type: cert.type,
        cert: cert.cert,
        key: cert.key,
        passphrase: this.passphrases.get(cert.id)
      }))
    }
    return Object.keys(network).length > 0 ? network : undefined
  }

  private proxyForCore(): Record<string, unknown> | undefined {
    const { mode, url, username, bypass } = this.stored.proxy
    if (mode === 'system') {
      const env: Record<string, string> = {}
      for (const name of PROXY_VARIABLES) {
        const value = process.env[name]
        if (value) {
          env[name] = value
        }
      }
      return { mode, env }
    }
    if (mode === 'manual') {
      return {
        mode,
        url,
        username: username || undefined,
        password: this.password || undefined,
        bypass: bypass.split(/[,\s]+/).filter(Boolean)
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
        log('network', 'no OS keyring; the proxy password is not persisted this session')
      }
      const certs = this.stored.certs.map((cert) => {
        const passphrase = this.passphrases.get(cert.id)
        if (!passphrase) {
          return cert
        }
        if (!safeStorage.isEncryptionAvailable()) {
          log('network', 'no OS keyring; a certificate passphrase is not persisted this session')
          return cert
        }
        return { ...cert, passphrase: safeStorage.encryptString(passphrase).toString('base64') }
      })
      writeFileSyncAtomic(this.file(), JSON.stringify({ proxy, certs }))
    } catch (error) {
      log('network', `could not write the network settings: ${String(error)}`)
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

/** Host patterns are NO_PROXY-style; an empty host would mean "everyone", which must be said as `*`. */
function validateCert(request: unknown): Required<Pick<ClientCertRequest, 'host' | 'type'>> & { passphrase: string } {
  const body = (request ?? {}) as Partial<ClientCertRequest>
  if (body.type !== 'pkcs12' && body.type !== 'pem') {
    throw new Error('A client certificate is either pkcs12 or pem')
  }
  const host = typeof body.host === 'string' ? body.host.trim() : ''
  if (!host) {
    throw new Error('Enter the host this certificate is for, for example api.example.com (or * for every host)')
  }
  if (host.length > 253 || !/^[A-Za-z0-9.*:_\-[\]]+([,\s]+[A-Za-z0-9.*:_\-[\]]+)*$/.test(host)) {
    throw new Error('The host may only contain letters, digits, dots, dashes, * and a port')
  }
  const passphrase = typeof body.passphrase === 'string' ? body.passphrase : ''
  if (passphrase.length > 1024) {
    throw new Error('The passphrase is too long')
  }
  return { host, type: body.type, passphrase }
}
