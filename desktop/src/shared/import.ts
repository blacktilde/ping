/**
 * What an import produced, as the renderer sees it.
 *
 * Shared because the main process builds it and the renderer shows it. Secret values are
 * deliberately absent: only how many were stored crosses the bridge.
 */
export interface ImportReport {
  collections: { path: string; name: string; requests: number; environments: number }[]
  warnings: string[]
  secretsStored: number
}
