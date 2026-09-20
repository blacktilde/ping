/**
 * Files a request body may read.
 *
 * The core reads the bytes (a large upload never crosses IPC), so what crosses the boundary is
 * a path. The renderer is sandboxed and not trusted to name one:
 *
 * - A file **inside the collection** is stored relative to the collection folder. It is
 *   portable in Git and needs no permission; the core refuses anything that escapes the folder.
 * - A file **anywhere else** is stored absolute, and is only readable if a file dialog chose it
 *   this session. Those are the *grants*. Any other absolute path is refused here, before the
 *   core sees it.
 *
 * Paths are literal: they are never interpolated, so no variable (including one captured from a
 * response) can turn a granted path into another file.
 */

import { realpathSync } from 'node:fs'
import { isAbsolute, relative, sep } from 'node:path'

/** Rejects anything that is not a plain path inside the folder it is resolved against. */
export function isRelativePath(value: string): boolean {
  if (value.startsWith('/') || value.startsWith('\\') || /^[a-zA-Z]:[\\/]/.test(value)) {
    return false
  }
  return !value.split(/[\\/]/).includes('..')
}

/** True for a path that names a location by itself, whichever platform wrote it. */
export function looksAbsolute(value: string): boolean {
  return isAbsolute(value) || value.startsWith('/') || value.startsWith('\\') || /^[a-zA-Z]:[\\/]/.test(value)
}

export class FileGrants {
  private readonly granted = new Set<string>()

  /** Records a file a dialog chose. The real path is kept, so a symlink cannot smuggle another target in. */
  grant(chosen: string): void {
    this.granted.add(realpathSync(chosen))
  }

  has(path: string): boolean {
    try {
      return this.granted.has(realpathSync(path))
    } catch {
      return false // Missing or unreadable: not something a dialog handed out.
    }
  }
}

/**
 * How a chosen file is stored, and whether it needs a grant.
 *
 * @param collectionDir the absolute collection folder, or null when the request has none
 */
export function storedPathFor(
  chosen: string,
  collectionDir: string | null
): { stored: string; grant: boolean } {
  const real = realpathSync(chosen)
  if (collectionDir) {
    try {
      const inside = relative(realpathSync(collectionDir), real)
      if (inside && !inside.startsWith('..') && !isAbsolute(inside)) {
        return { stored: inside.split(sep).join('/'), grant: false }
      }
    } catch {
      // The collection folder is gone; fall through to an absolute, granted path.
    }
  }
  return { stored: real, grant: true }
}

interface BodyFile {
  path: string
  label: string
}

/** Every file path a request body names, skipping rows the core will skip. */
function bodyFiles(params: unknown): BodyFile[] {
  const body = (params as { body?: unknown } | null)?.body
  if (!body || typeof body !== 'object') {
    return []
  }
  const found: BodyFile[] = []
  const { type, file, fields } = body as { type?: unknown; file?: unknown; fields?: unknown }
  if (type === 'file' && typeof file === 'string' && file) {
    found.push({ path: file, label: 'the body' })
  }
  if (Array.isArray(fields)) {
    for (const field of fields as { name?: unknown; file?: unknown; enabled?: unknown }[]) {
      if (field && field.enabled !== false && typeof field.file === 'string' && field.file) {
        found.push({ path: field.file, label: `the field "${String(field.name ?? '')}"` })
      }
    }
  }
  return found
}

/**
 * @returns a message when the request names a file it may not read, or null when it is fine
 */
export function checkBodyFiles(params: unknown, grants: FileGrants): string | null {
  for (const { path, label } of bodyFiles(params)) {
    if (looksAbsolute(path)) {
      if (!grants.has(path)) {
        return `The file for ${label} was not chosen in this session. Choose it again.`
      }
    } else if (!isRelativePath(path)) {
      return `The file for ${label} is outside the collection.`
    }
  }
  return null
}
