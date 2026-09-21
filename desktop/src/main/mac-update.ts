/**
 * The macOS update path.
 *
 * Squirrel.Mac — what `electron-updater` drives on darwin — validates an update against the
 * running app's code signature before swapping it in. That check cannot pass without a paid
 * Apple Developer ID certificate: an unsigned or ad-hoc-signed build has a designated
 * requirement derived from the binary's own hash, which changes with every release, so every
 * install fails. Windows and Linux keep `electron-updater`; macOS gets this instead.
 *
 * What replaces Apple's signature is this project's own: the release publishes
 * `latest-mac.json`, signing `version:arch:sha512` with an Ed25519 key whose public half is
 * compiled in below. The app installs a download only when its bytes hash to a value that
 * signature covers, so the trust chain is the private key in the release workflow rather than
 * a certificate. `mac-update-verify.ts` holds that decision; this file does the I/O around it.
 *
 * The swap itself is a detached shell script, because a process cannot replace the bundle it
 * is running from. It waits for the app to exit, moves the old bundle aside, moves the new
 * one in, relaunches, and restores the backup if either move fails. A zip fetched here never
 * carries `com.apple.quarantine` — that attribute comes from a browser download — so the new
 * bundle launches without a Gatekeeper prompt.
 */

import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { app } from 'electron'
import {
  manifestArch,
  offerFrom,
  sha512Hex,
  signedMessage,
  verifySignature,
  type MacUpdateOffer
} from './mac-update-verify'

/** The feed. `releases/latest/download` always resolves to the newest published release. */
const MANIFEST_URL = 'https://github.com/dbohry/ping/releases/latest/download/latest-mac.json'

/**
 * The public half of the release signing key. Not a secret — it is the check, not the
 * capability. Its private half is the `MAC_UPDATE_SIGNING_KEY` repository secret the release
 * workflow signs with; see `tools/mac-update-keygen.sh`. Replacing this constant retires
 * every signature made with the old key, which is how a compromised key is rotated.
 */
const PUBLIC_KEY_PEM = `-----BEGIN PUBLIC KEY-----
REPLACE_WITH_THE_RELEASE_SIGNING_PUBLIC_KEY
-----END PUBLIC KEY-----
`

/** Where a verified bundle waits between "downloaded" and "restart and install". */
let staged: { version: string; appPath: string; stagedPath: string } | null = null

export function macUpdatesSupported(): boolean {
  return process.platform === 'darwin'
}

function signingKey(): string {
  if (PUBLIC_KEY_PEM.includes('REPLACE_WITH_THE_RELEASE_SIGNING_PUBLIC_KEY')) {
    throw new Error('This build carries no release signing key, so it cannot verify an update')
  }
  return PUBLIC_KEY_PEM
}

/**
 * The bundle this process runs from: `/Applications/Ping.app` for an execPath of
 * `/Applications/Ping.app/Contents/MacOS/Ping`.
 */
function bundlePaths(): { appPath: string; appsDir: string; appName: string } {
  const appPath = dirname(dirname(dirname(process.execPath)))
  return { appPath, appsDir: dirname(appPath), appName: basename(appPath) }
}

/**
 * The install has to be a rename next to the existing bundle, so a read-only or
 * root-owned location cannot be updated in place. Better to say so than to download 100MB
 * and fail at the last step.
 */
function assertInstallable(appsDir: string): void {
  let probe: string | null = null
  try {
    probe = mkdtempSync(join(appsDir, '.ping-update-probe-'))
  } catch {
    throw new Error(
      `Ping cannot update itself in ${appsDir}. Move Ping.app somewhere you can write to, or download the new version manually.`
    )
  } finally {
    if (probe) {
      rmSync(probe, { recursive: true, force: true })
    }
  }
}

/** Fetches the manifest and reports what it offers this machine, or null when nothing does. */
export async function findMacUpdate(): Promise<MacUpdateOffer | null> {
  signingKey()

  const response = await fetch(MANIFEST_URL, { redirect: 'follow' })
  if (!response.ok) {
    throw new Error(`The update feed answered ${response.status}`)
  }

  const offer = offerFrom(await response.json(), app.getVersion(), manifestArch())
  if (!offer) {
    return null
  }

  assertInstallable(bundlePaths().appsDir)
  return offer
}

async function download(
  url: string,
  onProgress: (transferred: number, total: number) => void
): Promise<Buffer> {
  const response = await fetch(url, { redirect: 'follow' })
  if (!response.ok || !response.body) {
    throw new Error(`The update download answered ${response.status}`)
  }

  const total = Number(response.headers.get('content-length') ?? 0)
  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let transferred = 0

  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    chunks.push(value)
    transferred += value.byteLength
    onProgress(transferred, total)
  }

  return Buffer.concat(chunks)
}

function run(command: string, args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args)
    let stderr = ''
    child.stderr.on('data', (chunk) => (stderr += String(chunk)))
    child.on('error', reject)
    child.on('close', (code) =>
      code === 0 ? resolve() : reject(new Error(`${command} exited ${code}: ${stderr.trim()}`))
    )
  })
}

/**
 * Downloads, verifies and unpacks the offered bundle beside the installed one, so the
 * install is a rename on the same filesystem. Nothing is unpacked before both the hash and
 * the signature check out.
 */
export async function stageMacUpdate(
  offer: MacUpdateOffer,
  onProgress: (transferred: number, total: number) => void
): Promise<void> {
  const key = signingKey()
  const { appPath, appsDir, appName } = bundlePaths()
  assertInstallable(appsDir)

  const zip = await download(offer.url, onProgress)

  const sha512 = sha512Hex(zip)
  if (sha512.toLowerCase() !== offer.sha512.toLowerCase()) {
    throw new Error('The download did not match the checksum the release published')
  }

  if (!verifySignature(key, signedMessage(offer.version, offer.arch, sha512), offer.signature)) {
    throw new Error('The download is not signed by this project — refusing to install it')
  }

  const staging = join(appsDir, '.ping-update-staging')
  rmSync(staging, { recursive: true, force: true })
  mkdirSync(staging, { recursive: true })

  const zipPath = join(staging, 'update.zip')
  writeFileSync(zipPath, zip)

  try {
    // ditto, not unzip: it is what preserves the bundle's symlinks, permissions and the
    // executable bit on the native core that travels inside it.
    await run('/usr/bin/ditto', ['-xk', zipPath, staging])
  } catch (cause) {
    rmSync(staging, { recursive: true, force: true })
    throw cause
  }

  const stagedPath = join(staging, appName)
  staged = { version: offer.version, appPath, stagedPath }
  rmSync(zipPath, { force: true })
}

export function macUpdateStaged(): boolean {
  return staged !== null
}

function quote(value: string): string {
  return `'${value.replaceAll("'", `'\\''`)}'`
}

/**
 * Hands the swap to a detached script and quits. Everything after this point runs without
 * the app: it waits for the bundle's processes — the shell and the native core it spawned —
 * to go, swaps the directories, and relaunches. A failed swap puts the old bundle back, so
 * the worst case is the version the user already had.
 */
export function installMacUpdate(): void {
  if (!staged) {
    return
  }

  const script = `#!/bin/sh
OLD_APP=${quote(staged.appPath)}
NEW_APP=${quote(staged.stagedPath)}
STAGING=${quote(dirname(staged.stagedPath))}

i=0
while pgrep -f "$OLD_APP/Contents" >/dev/null 2>&1; do
  i=$((i+1))
  [ "$i" -gt 60 ] && break
  sleep 0.5
done

BACKUP="$OLD_APP.old-$$"
if mv "$OLD_APP" "$BACKUP" && mv "$NEW_APP" "$OLD_APP"; then
  rm -rf "$BACKUP"
  rm -rf "$STAGING"
  open "$OLD_APP"
elif [ -d "$BACKUP" ]; then
  mv "$BACKUP" "$OLD_APP"
  open "$OLD_APP"
fi
rm -f "$0"
`

  const scriptPath = join(app.getPath('temp'), `ping-update-swap-${Date.now()}.sh`)
  writeFileSync(scriptPath, script, { mode: 0o755 })
  spawn('/bin/sh', [scriptPath], { detached: true, stdio: 'ignore' }).unref()
  app.quit()
}
