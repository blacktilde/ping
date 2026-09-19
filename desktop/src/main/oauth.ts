import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { app, safeStorage } from 'electron'

export interface OAuthTokens {
  accessToken: string
  refreshToken?: string
  expiresAtMillis?: number
}

/**
 * OAuth2 tokens, encrypted with safeStorage and kept out of the secrets list.
 *
 * Keyed by the grant the core computed, so a token obtained by authorizing is handed back
 * on the next send without the renderer ever holding it.
 */
export class OAuthTokenStore {
  private tokens = new Map<string, OAuthTokens>()
  private encrypted = false

  load(): void {
    this.encrypted = safeStorage.isEncryptionAvailable()
    if (!this.encrypted || !existsSync(this.file())) {
      return
    }
    try {
      const parsed = JSON.parse(safeStorage.decryptString(readFileSync(this.file()))) as Record<
        string,
        OAuthTokens
      >
      for (const [key, value] of Object.entries(parsed)) {
        this.tokens.set(key, value)
      }
    } catch (error) {
      process.stderr.write(`[oauth] could not read tokens: ${String(error)}\n`)
    }
  }

  get(key: string): OAuthTokens | undefined {
    return this.tokens.get(key)
  }

  set(key: string, tokens: OAuthTokens): void {
    this.tokens.set(key, tokens)
    this.persist()
  }

  private persist(): void {
    if (!this.encrypted) {
      process.stderr.write('[oauth] no OS keyring; tokens are not persisted this session\n')
      return
    }
    try {
      writeFileSync(this.file(), safeStorage.encryptString(JSON.stringify(Object.fromEntries(this.tokens))))
    } catch (error) {
      process.stderr.write(`[oauth] could not write tokens: ${String(error)}\n`)
    }
  }

  private file(): string {
    return join(app.getPath('userData'), 'oauth-tokens.bin')
  }
}
