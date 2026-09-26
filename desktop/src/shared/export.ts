/**
 * What an export produced, as the renderer sees it.
 *
 * Shared because the main process builds it and the renderer shows it. The document itself
 * stays in main: it goes straight from the core to the file the user chose.
 */
export interface ExportReport {
  /** Where the file was written; only shown, never handed back to the shell. */
  file: string
  name: string
  requests: number
  warnings: string[]
}
