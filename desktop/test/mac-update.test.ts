/**
 * The macOS updater installs a bundle that no Apple certificate vouches for, so these checks
 * are the whole of its trust: a download is installed only when it came from this project's
 * release assets and its bytes hash to something the release signed. Run with Node's own
 * test runner (`npm test`); Node strips the types.
 */

import assert from 'node:assert/strict'
import { generateKeyPairSync, sign } from 'node:crypto'
import { test } from 'node:test'
import {
  assetUrlAllowed,
  compareVersions,
  offerFrom,
  sha512Hex,
  signedMessage,
  verifySignature
} from '../src/main/mac-update-verify.ts'

const keys = generateKeyPairSync('ed25519')
const publicKeyPem = keys.publicKey.export({ type: 'spki', format: 'pem' }).toString()

function signMessage(message: string): string {
  return sign(null, Buffer.from(message), keys.privateKey).toString('base64')
}

function manifest(version: string, overrides: Record<string, unknown> = {}): unknown {
  const sha512 = sha512Hex(Buffer.from('a release'))
  return {
    version,
    arm64: {
      url: `https://github.com/dbohry/ping/releases/download/${version}/Ping-${version}-arm64-mac.zip`,
      sha512,
      signature: signMessage(signedMessage(version, 'arm64', sha512))
    },
    ...overrides
  }
}

test('versions order as numbers, not as text', () => {
  assert.equal(compareVersions('0.10.0', '0.9.0'), 1)
  assert.equal(compareVersions('0.1.7', '0.1.7'), 0)
  assert.equal(compareVersions('0.1', '0.1.1'), -1)
  assert.equal(compareVersions('1.0.0', '0.99.99'), 1)
})

test('an offer is the newer release for this architecture', () => {
  const offer = offerFrom(manifest('0.2.0'), '0.1.7', 'arm64')
  assert.equal(offer?.version, '0.2.0')
  assert.equal(offer?.arch, 'arm64')
})

test('the same or an older release offers nothing', () => {
  assert.equal(offerFrom(manifest('0.1.7'), '0.1.7', 'arm64'), null)
  assert.equal(offerFrom(manifest('0.1.0'), '0.1.7', 'arm64'), null)
})

test('an architecture the release does not build offers nothing', () => {
  assert.equal(offerFrom(manifest('0.2.0'), '0.1.7', 'x64'), null)
})

test('a manifest that cannot be read is an error, not an up-to-date app', () => {
  assert.throws(() => offerFrom(null, '0.1.7', 'arm64'))
  assert.throws(() => offerFrom({ arm64: {} }, '0.1.7', 'arm64'))
})

test('an asset hosted anywhere but the release is refused', () => {
  const hostile = manifest('0.2.0', {
    arm64: { url: 'https://example.com/Ping.zip', sha512: 'abc', signature: 'abc' }
  })
  assert.throws(() => offerFrom(hostile, '0.1.7', 'arm64'), /points outside the release assets/)
  assert.equal(assetUrlAllowed('https://github.com/dbohry/ping-evil/releases/download/1/a.zip'), false)
  assert.equal(assetUrlAllowed('https://github.com/dbohry/ping/releases/download/0.2.0/a.zip'), true)
})

test('only a signature over the exact version, architecture and hash verifies', () => {
  const sha512 = sha512Hex(Buffer.from('a release'))
  const message = signedMessage('0.2.0', 'arm64', sha512)
  const signature = signMessage(message)

  assert.equal(verifySignature(publicKeyPem, message, signature), true)
  assert.equal(verifySignature(publicKeyPem, signedMessage('0.3.0', 'arm64', sha512), signature), false)
  assert.equal(verifySignature(publicKeyPem, signedMessage('0.2.0', 'x64', sha512), signature), false)
  assert.equal(
    verifySignature(publicKeyPem, signedMessage('0.2.0', 'arm64', sha512Hex(Buffer.from('other'))), signature),
    false
  )
})

test('a signature from another key never verifies', () => {
  const other = generateKeyPairSync('ed25519')
  const message = signedMessage('0.2.0', 'arm64', sha512Hex(Buffer.from('a release')))
  const forged = sign(null, Buffer.from(message), other.privateKey).toString('base64')

  assert.equal(verifySignature(publicKeyPem, message, forged), false)
})

test('a build with no signing key configured cannot verify anything', () => {
  const placeholder = '-----BEGIN PUBLIC KEY-----\nREPLACE_ME\n-----END PUBLIC KEY-----\n'
  assert.equal(verifySignature(placeholder, 'anything', 'AAAA'), false)
})
