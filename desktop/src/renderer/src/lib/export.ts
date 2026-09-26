/**
 * Exporting a collection for other tools.
 *
 * The core builds the document and the shell saves it; the renderer only names the collection
 * and shows what came of it.
 */

import { CoreError } from './core'
import type { ExportReport } from '../../../shared/export'

export type { ExportReport }

/**
 * Asks the shell to export collections as Postman v2.1 files, one per collection.
 *
 * @param paths the collections, relative to the open folder
 * @returns the report, or null when the user dismissed the dialog
 */
export async function exportCollectionFiles(paths: string[]): Promise<ExportReport | null> {
  const result = await window.ping.exportCollections(paths)
  if (result.ok) {
    return result.value
  }
  throw new CoreError(result.error.code, result.error.message)
}
