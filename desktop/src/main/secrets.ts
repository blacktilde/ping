import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { app, safeStorage } from 'electron'

/**
 * Secret values, encrypted with Electron safeStorage.
 *
 * Only names ever cross to the renderer; values go straight into `core:request`. When the
 * OS keyring is unavailable the values live in memory for the session instead of being
 * written in the clear — persistence is a convenience, not a reason to leak.
 */
export class SecretStore {
  private values = new Map<string, string>()
  private encrypted = false

  load(): void {
    this.encrypted = safeStorage.isEncryptionAvailable()
    if (!this.encrypted || !existsSync(this.file())) {
      return
    }
    try {
      const decrypted = safeStorage.decryptString(readFileSync(this.file()))
      const parsed = JSON.parse(decrypted) as Record<string, string>
      for (const [name, value] of Object.entries(parsed)) {
        this.values.set(name, value)
      }
    } catch (error) {
      process.stderr.write(`[secrets] could not read the secret store: ${String(error)}\n`)
    }
  }

  names(): string[] {
    return [...this.values.keys()].sort()
  }

  /** Every value, for merging into an outgoing request. Never sent to the renderer. */
  all(): Record<string, string> {
    return Object.fromEntries(this.values)
  }

  set(name: string, value: string): void {
    const key = name.trim()
    if (!key) {
      return
    }
    this.values.set(key, value)
    this.persist()
  }

  delete(name: string): void {
    this.values.delete(name)
    this.persist()
  }

  private persist(): void {
    if (!this.encrypted) {
      process.stderr.write('[secrets] no OS keyring; secrets are not persisted this session\n')
      return
    }
    try {
      writeFileSync(this.file(), safeStorage.encryptString(JSON.stringify(this.all())))
    } catch (error) {
      process.stderr.write(`[secrets] could not write the secret store: ${String(error)}\n`)
    }
  }

  private file(): string {
    return join(app.getPath('userData'), 'secrets.bin')
  }
}
