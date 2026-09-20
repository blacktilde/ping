/**
 * The names of the runtime variables the shell is holding.
 *
 * The values live in the main process and never cross to the renderer; this only mirrors
 * which names exist, so the Variables panel can show them and offer Clear.
 */

export const runtime = $state<{ names: string[] }>({ names: [] })

export async function refreshRuntime(): Promise<void> {
  try {
    runtime.names = await window.ping.runtime.list()
  } catch {
    // The list is a courtesy: a failure here must not disturb a send.
  }
}

export async function clearRuntime(): Promise<void> {
  await window.ping.runtime.clear()
  runtime.names = []
}
