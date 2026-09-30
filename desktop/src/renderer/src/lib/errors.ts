/**
 * Turns a failed exchange into something a person can act on.
 *
 * The core already rewrites Java exceptions into short messages (`HttpEngine.describe`); this
 * module classifies those messages, so the UI can say what probably went wrong, name the stage of
 * the connection that failed, and offer the fix that fits. The raw message always stays available:
 * a classification is a guess, and the guess must never hide what the core said.
 */
import type { ProbeResult } from './probe'

export type ErrorKind =
  | 'dns'
  | 'refused'
  | 'timeout'
  | 'tls-untrusted'
  | 'tls-client-cert'
  | 'tls'
  | 'closed'
  | 'url'
  | 'other'

export type StageKey = 'dns' | 'connect' | 'tls' | 'request' | 'response'

/** A one-click fix the card can offer; the card decides how each is carried out. */
export type Remedy = 'network-settings' | 'skip-tls'

export interface ErrorInfo {
  kind: ErrorKind
  title: string
  summary: string
  hints: string[]
  /** The connection stage that failed, when the message says. */
  stage?: StageKey
  remedies: Remedy[]
}

export interface StageRow {
  key: StageKey
  label: string
  state: 'ok' | 'fail' | 'idle'
  /** Only known once a connection check has timed the stage. */
  ms?: number
}

/** scheme://host[:port] of a url without variables in it, or null when it cannot be probed. */
export function originOf(url: string): string | null {
  if (url.includes('{{')) return null
  try {
    const parsed = new URL(url.trim())
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.origin : null
  } catch {
    return null
  }
}

function hostOf(url: string): string {
  const origin = originOf(url)
  return origin ? new URL(origin).host : ''
}

export function classify(message: string): ErrorKind {
  const text = message.trim()
  if (/^(Malformed url|Url must be absolute|A url is required)/i.test(text)) return 'url'
  if (/^Unknown host/i.test(text)) return 'dns'
  if (/timed out/i.test(text)) return 'timeout'
  if (/^TLS handshake failed/i.test(text) || /\b(SSL|certificate)\b/i.test(text)) {
    if (/client certificate|certificate_required|bad_certificate/i.test(text)) return 'tls-client-cert'
    if (/PKIX|certification path|self.signed|unable to find valid|not trusted|expired|does not match|No subject alternative/i.test(text)) {
      return 'tls-untrusted'
    }
    return 'tls'
  }
  if (/closed the connection without answering|received no bytes/i.test(text)) return 'closed'
  if (/refused/i.test(text)) return 'refused'
  return 'other'
}

export function describeError(message: string, url: string): ErrorInfo {
  const kind = classify(message)
  const host = hostOf(url)
  const target = host || 'the server'
  const https = /^https:/i.test(url.trim())

  switch (kind) {
    case 'dns':
      return {
        kind,
        stage: 'dns',
        title: `Couldn’t find ${host || 'that host'}`,
        summary: 'The name didn’t resolve to an address. It may be misspelled, or this machine may be offline.',
        hints: ['Check the host name for typos.', 'If it is an internal name, check that you are on the right network or VPN.'],
        remedies: []
      }
    case 'refused':
      return {
        kind,
        stage: 'connect',
        title: `Couldn’t connect to ${target}`,
        summary: 'Nothing accepted the connection. The server may not be running, or the port is wrong.',
        hints: ['Is the server started?', 'Check the port, and which environment is selected.'],
        remedies: []
      }
    case 'timeout':
      return {
        kind,
        title: `${target === 'the server' ? 'The server' : target} took too long to answer`,
        summary: 'The request hit its time limit before a response arrived.',
        hints: ['The server may be slow or overloaded.', 'A longer timeout can be set in the request’s Settings tab.'],
        remedies: []
      }
    case 'tls-untrusted':
      return {
        kind,
        stage: 'tls',
        title: 'The server’s certificate isn’t trusted',
        summary: `${target} presented a certificate this machine can’t verify. Nothing was sent.`,
        hints: [],
        remedies: ['network-settings', 'skip-tls']
      }
    case 'tls-client-cert':
      return {
        kind,
        stage: 'tls',
        title: 'The server wants a client certificate',
        summary: `${target} refused the connection without one. Nothing was sent.`,
        hints: [],
        remedies: ['network-settings']
      }
    case 'tls':
      return {
        kind,
        stage: 'tls',
        title: 'The secure connection failed',
        summary: `Ping couldn’t finish the TLS handshake with ${target}.`,
        hints: https ? ['The server may not speak HTTPS on this port; try http:// if it is a local service.'] : [],
        remedies: ['skip-tls']
      }
    case 'closed':
      return {
        kind,
        stage: 'response',
        title: `${target === 'the server' ? 'The server' : target} hung up`,
        summary: 'The connection was closed before any response came back.',
        hints: ['A server that requires a client certificate does this; add one in Network settings if so.'],
        remedies: ['network-settings']
      }
    case 'url':
      return {
        kind,
        title: 'The URL isn’t valid',
        summary: message,
        hints: ['A URL needs a scheme and a host, like https://example.com/path.'],
        remedies: []
      }
    default:
      return { kind, title: 'The request failed', summary: message, hints: [], remedies: [] }
  }
}

/**
 * The connection as a row of stages. Stages before the failed one are marked reached (the failure
 * could not come from a stage the connection had not yet got to) and those after did not run;
 * a connection check, when there is one, adds each stage's time and can move the failure.
 */
export function stageRows(info: ErrorInfo, url: string, probe: ProbeResult | null = null): StageRow[] {
  const failed: StageKey | undefined = probe?.failedStage
    ? probe.failedStage === 'tunnel'
      ? 'connect'
      : probe.failedStage
    : info.stage
  if (!failed) return []

  // A url that starts with a variable has no scheme to read; TLS is shown only when it is known to matter.
  const https = /^https:/i.test(url.trim()) || failed === 'tls'
  const rows: { key: StageKey; label: string; ms?: number }[] = [
    { key: 'dns', label: 'DNS', ms: probe?.dnsMs },
    { key: 'connect', label: 'Connect', ms: probe?.connectMs },
    ...(https ? [{ key: 'tls' as const, label: 'TLS', ms: probe?.tlsMs }] : []),
    { key: 'request', label: 'Request' },
    { key: 'response', label: 'Response' }
  ]
  const at = rows.findIndex((row) => row.key === failed)
  return rows.map((row, index) => ({
    ...row,
    state: index < at ? 'ok' : index === at ? 'fail' : 'idle'
  }))
}
