/**
 * The decisions that make a macOS update safe to install, kept apart from the I/O so they
 * can be tested directly.
 *
 * macOS updates do not go through Squirrel.Mac (see `mac-update.ts` for why), so the
 * signature Apple would have checked is replaced by one this project makes itself: every
 * release signs `version:arch:sha512` with an Ed25519 key whose public half is compiled into
 * the app. A download is installed only when its bytes hash to a value that signature
 * covers, and only when it came from this project's own release assets.
 */

import { createHash, verify } from 'node:crypto'

/**
 * Release assets live under the project's public repository and nowhere else. The signature
 * already pins the bytes, so a hostile manifest cannot swap the payload — but it could still
 * name any host on earth, and a URL the app fetches on launch is not a good place to leave
 * that open.
 */
const ASSET_PREFIX = 'https://github.com/dbohry/ping/releases/download/'

/** One architecture's entry in `latest-mac.json`. */
export interface MacUpdateEntry {
  url: string
  sha512: string
  signature: string
}

/** An entry the manifest offers for this machine, with the version it belongs to. */
export interface MacUpdateOffer extends MacUpdateEntry {
  version: string
  arch: string
}

/**
 * Compares two dotted numeric versions, ordering them as numbers rather than as text so
 * 0.10.0 sorts above 0.9.0. Missing and unparsable parts count as zero.
 */
export function compareVersions(a: string, b: string): number {
  const left = a.split('.')
  const right = b.split('.')
  const length = Math.max(left.length, right.length)

  for (let i = 0; i < length; i++) {
    const one = Number.parseInt(left[i] ?? '0', 10) || 0
    const other = Number.parseInt(right[i] ?? '0', 10) || 0
    if (one !== other) {
      return one < other ? -1 : 1
    }
  }

  return 0
}

/** The manifest key for a process architecture. Only arm64 is published today. */
export function manifestArch(arch: string = process.arch): string {
  return arch === 'arm64' ? 'arm64' : 'x64'
}

/** Exactly what the release signs, and exactly what the app verifies. */
export function signedMessage(version: string, arch: string, sha512: string): string {
  return `${version}:${arch}:${sha512}`
}

export function sha512Hex(bytes: Uint8Array): string {
  return createHash('sha512').update(bytes).digest('hex')
}

/** True when `signature` is this project's Ed25519 signature over `message`. */
export function verifySignature(
  publicKeyPem: string,
  message: string,
  signatureBase64: string
): boolean {
  try {
    return verify(null, Buffer.from(message), publicKeyPem, Buffer.from(signatureBase64, 'base64'))
  } catch {
    // A malformed key or signature is a failed verification, not a crash on launch.
    return false
  }
}

export function assetUrlAllowed(url: string): boolean {
  return url.startsWith(ASSET_PREFIX)
}

function entryOf(value: unknown): MacUpdateEntry | null {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const { url, sha512, signature } = value as Record<string, unknown>
  if (typeof url !== 'string' || typeof sha512 !== 'string' || typeof signature !== 'string') {
    return null
  }
  return { url, sha512, signature }
}

/**
 * Reads the manifest and returns what it offers this machine, or null when there is nothing
 * to install: the release is not newer, or it carries no build for this architecture (an
 * Intel Mac today). A manifest that cannot be read at all throws, because that is a broken
 * feed rather than an up-to-date app.
 */
export function offerFrom(manifest: unknown, currentVersion: string, arch: string): MacUpdateOffer | null {
  if (typeof manifest !== 'object' || manifest === null) {
    throw new Error('The update manifest is not an object')
  }

  const { version } = manifest as Record<string, unknown>
  if (typeof version !== 'string' || version === '') {
    throw new Error('The update manifest carries no version')
  }

  if (compareVersions(currentVersion, version) >= 0) {
    return null
  }

  const entry = entryOf((manifest as Record<string, unknown>)[arch])
  if (!entry) {
    return null
  }

  if (!assetUrlAllowed(entry.url)) {
    throw new Error(`The update manifest points outside the release assets: ${entry.url}`)
  }

  return { ...entry, version, arch }
}
