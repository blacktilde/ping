/** The user's proxy settings via the shell. The password is write-only: only its presence is known. */
import type { NetworkSettings, NetworkUpdate } from '../../../shared/network'

export function loadNetwork(): Promise<NetworkSettings> {
  return window.ping.network.get()
}

export function saveNetwork(update: NetworkUpdate): Promise<NetworkSettings> {
  return window.ping.network.set(update)
}
