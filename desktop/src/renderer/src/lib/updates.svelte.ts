/**
 * The updater state the shell pushed last.
 *
 * Module-level `$state`, like the other stores: the badge and the command palette both read
 * it, and no single component owns it. Every command returns the resulting state, so the UI
 * does not have to wait for the next pushed event to reflect a click.
 *
 * `requested` separates a check the user asked for from the one the shell runs on launch.
 * A background check that finds nothing is silent; only a requested one is worth a badge
 * saying so, and only until it fades.
 */

import type { UpdateState } from '../../../shared/updates'

export const updates = $state<{
  state: UpdateState
  requested: boolean
}>({
  state: { status: 'idle', enabled: false, version: null, progress: null, error: null },
  requested: false
})

/** Drops the outcome of a requested check, so the badge goes quiet again. */
export function clearRequested(): void {
  updates.requested = false
}

export async function loadUpdateState(): Promise<void> {
  updates.state = await window.ping.updates.state()
}

export function watchUpdates(): () => void {
  return window.ping.updates.onState((state) => (updates.state = state))
}

export async function checkForUpdates(): Promise<void> {
  updates.requested = true
  updates.state = await window.ping.updates.check()
}

export async function downloadUpdate(): Promise<void> {
  updates.state = await window.ping.updates.download()
}

export async function installUpdate(): Promise<void> {
  updates.state = await window.ping.updates.install()
}
