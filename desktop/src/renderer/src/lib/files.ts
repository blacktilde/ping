/**
 * Files for request bodies.
 *
 * A path is never typed: it comes from a dialog the shell runs, which is what lets the shell
 * vouch for it. The shell stores a file inside the collection relative to it (portable in Git)
 * and grants a file elsewhere for this session only, so a restored tab or an imported path
 * outside the collection asks to be chosen again.
 */

export interface PickedFile {
  /** What the request keeps: relative to the collection, or absolute for a file elsewhere. */
  stored: string
  name: string
  size: number
}

export function pickFile(collection: string): Promise<PickedFile | null> {
  return window.ping.pickFile(collection)
}

/** The file's own name, for showing a stored path without its directories. */
export function fileLabel(path: string | undefined): string {
  if (!path) {
    return ''
  }
  const parts = path.split(/[\\/]/)
  return parts[parts.length - 1]
}
