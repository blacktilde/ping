/**
 * The cookies the core is holding for the active collection and environment.
 *
 * Only what a cookie is and where it applies: its value is a session credential and never
 * reaches the renderer. The jar itself lives in the core; this mirrors it for the panel.
 */

import type { CookieView } from '../../../shared/cookies'

export const cookies = $state<{ items: CookieView[] }>({ items: [] })

export async function refreshCookies(collection: string, environment: string): Promise<void> {
  try {
    cookies.items = await window.ping.cookies.list(collection, environment)
  } catch {
    // The list is a courtesy: a failure here must not disturb a send.
  }
}

export async function clearCookies(
  collection: string,
  environment: string,
  domain?: string,
  name?: string
): Promise<void> {
  await window.ping.cookies.clear(collection, environment, domain, name)
  await refreshCookies(collection, environment)
}
