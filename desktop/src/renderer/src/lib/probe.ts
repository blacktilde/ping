/**
 * The connection probe: a separate connection, made on request, that times each stage.
 *
 * Mirrors `contract/net.schema.json`. It is not part of any exchange's timing, and the UI says so.
 */
import { call } from './core'

export interface ProbeCertificate {
  subject: string
  issuer: string
  notBefore: string
  notAfter: string
  altNames?: string[]
  chainLength: number
}

export interface ProbeResult {
  origin: string
  connectedTo: string
  viaProxy: boolean
  addresses?: string[]
  dnsMs?: number
  connectMs?: number
  tunnelMs?: number
  tlsMs?: number
  protocol?: string
  cipherSuite?: string
  alpn?: string
  verified?: boolean
  certificate?: ProbeCertificate
  failedStage?: 'dns' | 'connect' | 'tunnel' | 'tls'
  error?: string
}

/** Only the origin is sent: the request's path and query may hold a credential. */
export function probeOrigin(origin: string, verifyTls: boolean): Promise<ProbeResult> {
  return call<ProbeResult>('net.probe', { url: origin, verifyTls })
}

/** How the certificate's validity reads: expired, soon, or nothing to say. */
export function expiryNote(notAfter: string, now = Date.now()): { text: string; warn: boolean } | null {
  const end = Date.parse(notAfter)
  if (Number.isNaN(end)) return null
  const days = Math.floor((end - now) / 86_400_000)
  if (end < now) return { text: 'expired', warn: true }
  if (days < 14) return { text: `expires in ${days} day${days === 1 ? '' : 's'}`, warn: true }
  return null
}
