/**
 * Writes and signs `latest-mac.json`, the feed the macOS updater reads.
 *
 * macOS updates do not go through Squirrel.Mac — it cannot install into a build without an
 * Apple Developer ID signature — so the app checks a signature this project makes instead:
 * Ed25519 over `version:arch:sha512`, verified against the public key compiled into
 * `desktop/src/main/mac-update.ts`. The private half is the MAC_UPDATE_SIGNING_KEY secret and
 * exists only in the release workflow; `tools/mac-update-keygen.sh` generates the pair.
 *
 * Usage: MAC_UPDATE_SIGNING_KEY=... node tools/mac-manifest.mjs <dist-dir> <version> <tag>
 */

import { createHash, createPrivateKey, sign } from 'node:crypto'
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const REPO = 'dbohry/ping'

/**
 * A PEM that has been through GitHub's secret UI comes back in a few shapes: real newlines,
 * literal "\n", or just the base64 body. Rebuild a well-formed PKCS#8 PEM from any of them.
 */
function normalizeKey(raw) {
  const base64 = raw
    .replaceAll('\\n', '\n')
    .replace(/-----[^-]+-----/g, '')
    .replace(/\s+/g, '')
  const lines = base64.match(/.{1,64}/g) ?? []
  return `-----BEGIN PRIVATE KEY-----\n${lines.join('\n')}\n-----END PRIVATE KEY-----\n`
}

/** electron-builder names the arm64 zip `-arm64-mac.zip` and the x64 one plain `-mac.zip`. */
function archOf(fileName) {
  return fileName.endsWith('-arm64-mac.zip') ? 'arm64' : 'x64'
}

const [distDir, version, tag] = process.argv.slice(2)
if (!distDir || !version || !tag) {
  throw new Error('Usage: node tools/mac-manifest.mjs <dist-dir> <version> <tag>')
}
if (!process.env.MAC_UPDATE_SIGNING_KEY) {
  throw new Error('MAC_UPDATE_SIGNING_KEY is required to sign the macOS update manifest')
}

const privateKey = createPrivateKey(normalizeKey(process.env.MAC_UPDATE_SIGNING_KEY))
const zips = readdirSync(distDir).filter((name) => name.endsWith('-mac.zip'))
if (zips.length === 0) {
  throw new Error(`No macOS update archive in ${distDir}; electron-builder must produce a zip target`)
}

const manifest = { version }
for (const fileName of zips) {
  const bytes = readFileSync(join(distDir, fileName))
  const sha512 = createHash('sha512').update(bytes).digest('hex')
  const arch = archOf(fileName)
  const signature = sign(null, Buffer.from(`${version}:${arch}:${sha512}`), privateKey)

  manifest[arch] = {
    url: `https://github.com/${REPO}/releases/download/${tag}/${encodeURIComponent(fileName)}`,
    sha512,
    signature: signature.toString('base64')
  }
  console.log(`Signed ${fileName} as ${arch}`)
}

writeFileSync(join(distDir, 'latest-mac.json'), `${JSON.stringify(manifest, null, 2)}\n`)
console.log(`Wrote ${join(distDir, 'latest-mac.json')}`)
