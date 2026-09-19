/** Display helpers shared by the response views. */

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

export function versionLabel(version: string): string {
  return version === 'HTTP_2' ? 'HTTP/2' : 'HTTP/1.1'
}

export function statusTone(status: number): string {
  if (status >= 200 && status < 300) return 'text-emerald-400'
  if (status >= 300 && status < 400) return 'text-amber-400'
  if (status >= 400) return 'text-red-400'
  return 'text-neutral-300'
}
