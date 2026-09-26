/**
 * The shell's half of exporting a collection.
 *
 * The core builds the document from the collection folder and writes nothing; the shell picks
 * where it goes. Kept apart from `index.ts` so it can be unit tested without Electron.
 */

import type { ExportReport } from '../shared/export'

interface CoreExportResult {
  name?: unknown
  content?: unknown
  requests?: unknown
  warnings?: unknown
}

/**
 * The name the save dialog suggests: `Shop API.postman_collection.json`, the suffix Postman
 * itself uses, with anything a file system would refuse replaced.
 */
export function exportFileName(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|\r\n]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 120)
  return `${cleaned.length > 0 ? cleaned : 'collection'}.postman_collection.json`
}

/** Checks what the core returned before any of it is written or shown. */
export function readExport(result: unknown): { name: string; content: string; requests: number; warnings: string[] } {
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

export function exportReport(file: string, exported: ReturnType<typeof readExport>): ExportReport {
  return { file, name: exported.name, requests: exported.requests, warnings: exported.warnings }
}
