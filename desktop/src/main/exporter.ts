/**
 * The shell's half of exporting collections.
 *
 * The core builds each document from its collection folder and writes nothing; the shell picks
 * where they go. Kept apart from `index.ts` so it can be unit tested without Electron.
 */

import type { ExportReport } from '../shared/export'

/** Postman's own suffix, so the file is recognised on sight. */
const SUFFIX = '.postman_collection.json'

interface CoreExportResult {
  name?: unknown
  content?: unknown
  requests?: unknown
  warnings?: unknown
}

export interface Exported {
  name: string
  content: string
  requests: number
  warnings: string[]
}

/**
 * The name suggested for a collection's file: `Shop API.postman_collection.json`, with
 * anything a file system would refuse replaced.
 */
export function exportFileName(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|\r\n]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 120)
  return `${cleaned.length > 0 ? cleaned : 'collection'}${SUFFIX}`
}

/**
 * File names for several collections written into one folder. Two collections can share a
 * display name, and the folder may already hold an earlier export, so a taken name becomes
 * `Name 2`: an export into a folder never overwrites a file the user did not pick by name.
 */
export function uniqueFileNames(names: string[], taken: (fileName: string) => boolean): string[] {
  const used = new Set<string>()
  return names.map((name) => {
    const first = exportFileName(name)
    const stem = first.slice(0, -SUFFIX.length)
    let candidate = first
    for (let n = 2; used.has(candidate.toLowerCase()) || taken(candidate); n++) {
      candidate = `${stem} ${n}${SUFFIX}`
    }
    used.add(candidate.toLowerCase())
    return candidate
  })
}

/** Checks what the core returned before any of it is written or shown. */
export function readExport(result: unknown): Exported {
  const value = (result ?? {}) as CoreExportResult
  if (typeof value.content !== 'string') {
    throw new Error('The core returned no export')
  }
  return {
    name: typeof value.name === 'string' ? value.name : 'collection',
    content: value.content,
    requests: typeof value.requests === 'number' ? value.requests : 0,
    warnings: Array.isArray(value.warnings)
      ? value.warnings.filter((warning): warning is string => typeof warning === 'string')
      : []
  }
}

/**
 * The report the renderer sees: where each file went and what was left out, never the content.
 * With more than one collection each warning names the collection it came from.
 */
export function exportReport(written: { exported: Exported; file: string }[]): ExportReport {
  const several = written.length > 1
  return {
    files: written.map(({ exported, file }) => ({
      name: exported.name,
      requests: exported.requests,
      file
    })),
    warnings: written.flatMap(({ exported }) =>
      several ? exported.warnings.map((warning) => `${exported.name}: ${warning}`) : exported.warnings
    )
  }
}
