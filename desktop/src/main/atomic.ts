import { renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { rename, unlink, writeFile } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { basename, dirname, join } from 'node:path'

/**
 * Crash-safe writes for shell-side stores, mirroring `YamlStore.writeValue` in the core:
 * write a temp file beside the target, then rename it into place. A rename within a
 * directory is atomic, so a crash mid-write leaves the previous file intact rather than a
 * truncated one. The temp file is a dotfile so the core's collection watcher does not
 * mistake it for data the user wrote.
 */

function tempPath(target: string): string {
  return join(dirname(target), `.${basename(target)}.${randomBytes(4).toString('hex')}.tmp`)
}

export function writeFileSyncAtomic(target: string, data: string | Buffer): void {
  const temp = tempPath(target)
  try {
    writeFileSync(temp, data)
    renameSync(temp, target)
  } catch (error) {
    try {
      unlinkSync(temp)
    } catch {
      // Nothing to clean up, or the temp write itself failed; the original error is what matters.
    }
    throw error
  }
}

export async function writeFileAtomic(target: string, data: string | Buffer): Promise<void> {
  const temp = tempPath(target)
  try {
    await writeFile(temp, data)
    await rename(temp, target)
  } catch (error) {
    await unlink(temp).catch(() => {})
    throw error
  }
}
