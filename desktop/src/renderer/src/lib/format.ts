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

/** A probe stage: loopback stages are far under a millisecond, so keep the tenth. */
export function formatProbeMs(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`
  return ms >= 100 ? `${Math.round(ms)} ms` : `${ms.toFixed(1)} ms`
}

export function versionLabel(version: string): string {
  return version === 'HTTP_2' ? 'HTTP/2' : 'HTTP/1.1'
}

export function statusTone(status: number): string {
  if (status >= 200 && status < 300) return 'text-success'
  if (status >= 300 && status < 400) return 'text-warning'
  if (status >= 400) return 'text-danger'
  return 'text-fg-muted'
}

/**
 * The colour a verb wears in the URL bar. Read-only verbs sit on the calm end and the ones
 * that change something on the server sit on the loud end, so a DELETE is hard to mistake
 * for a GET at a glance. The shades are theme variables (see app.css): the dark ones are
 * unreadable on a light panel, so a light theme redefines them.
 */
export function methodTone(method: string): string {
  switch (method) {
    case 'GET':
      return 'text-verb-get'
    case 'POST':
      return 'text-verb-post'
    case 'PUT':
      return 'text-verb-put'
    case 'PATCH':
      return 'text-verb-patch'
    case 'DELETE':
      return 'text-verb-delete'
    default:
      return 'text-fg-muted'
  }
}

const REASONS: Record<number, string> = {
  100: 'Continue', 101: 'Switching Protocols', 200: 'OK', 201: 'Created', 202: 'Accepted',
  203: 'Non-Authoritative Information', 204: 'No Content', 205: 'Reset Content',
  206: 'Partial Content', 301: 'Moved Permanently', 302: 'Found', 303: 'See Other',
  304: 'Not Modified', 307: 'Temporary Redirect', 308: 'Permanent Redirect',
  400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found',
  405: 'Method Not Allowed', 406: 'Not Acceptable', 408: 'Request Timeout', 409: 'Conflict',
  410: 'Gone', 412: 'Precondition Failed', 413: 'Payload Too Large',
  415: 'Unsupported Media Type', 418: "I'm a teapot", 422: 'Unprocessable Content',
  429: 'Too Many Requests', 500: 'Internal Server Error', 501: 'Not Implemented',
  502: 'Bad Gateway', 503: 'Service Unavailable', 504: 'Gateway Timeout'
}

/**
 * The standard reason phrase for a status, or '' when there is none. HTTP/2 carries no reason
 * on the wire, so this is the registry's wording rather than what the server said.
 */
export function reasonPhrase(status: number): string {
  return REASONS[status] ?? ''
}
