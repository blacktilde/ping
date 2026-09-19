/** Secret names and values via the shell. Values are write-only: they are never read back. */

export function listSecrets(): Promise<string[]> {
  return window.ping.secrets.list()
}

export function setSecret(name: string, value: string): Promise<void> {
  return window.ping.secrets.set(name, value)
}

export function deleteSecret(name: string): Promise<void> {
  return window.ping.secrets.remove(name)
}
