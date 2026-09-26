/**
 * What an export produced, as the renderer sees it.
 *
 * Shared because the main process builds it and the renderer shows it. The documents themselves
 * stay in main: they go straight from the core to the files the user chose.
 */
export interface ExportReport {
  /** One per collection, in the order they were asked for. */
  files: { name: string; requests: number; file: string }[]
  /** What could not be carried over; prefixed with the collection's name when there are several. */
  warnings: string[]
}
