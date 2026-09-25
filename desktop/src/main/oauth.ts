import { existsSync, readFileSync } from 'node:fs'
import { writeFileSyncAtomic } from './atomic'
import { join } from 'node:path'
import { app, safeStorage } from 'electron'
import { log } from './log'

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
      log('oauth', `could not read tokens: ${String(error)}`)
    }
  }

  get(key: string): OAuthTokens | undefined {
    return this.tokens.get(key)
  }

  /** Every stored token, for the log to mask. Never sent anywhere. */
  sensitiveValues(): string[] {
    return [...this.tokens.values()].flatMap((tokens) =>
      [tokens.accessToken, tokens.refreshToken].filter((value): value is string => !!value)
    )
  }

  set(key: string, tokens: OAuthTokens): void {
    this.tokens.set(key, tokens)
    this.persist()
  }

  private persist(): void {
    if (!this.encrypted) {
      log('oauth', 'no OS keyring; tokens are not persisted this session')
      return
    }
    try {
      writeFileSyncAtomic(this.file(), safeStorage.encryptString(JSON.stringify(Object.fromEntries(this.tokens))))
    } catch (error) {
      log('oauth', `could not write tokens: ${String(error)}`)
    }
  }

  private file(): string {
    return join(app.getPath('userData'), 'oauth-tokens.bin')
  }
}
