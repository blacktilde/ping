/** The user's proxy settings via the shell. The password is write-only: only its presence is known. */
import type { ClientCertRequest, NetworkSettings, NetworkUpdate } from '../../../shared/network'

export function loadNetwork(): Promise<NetworkSettings> {
  return window.ping.network.get()
}

export function saveNetwork(update: NetworkUpdate): Promise<NetworkSettings> {
  return window.ping.network.set(update)
}

/** The main process opens the file dialog(s); a dismissed dialog leaves the list unchanged. */
export function addClientCert(request: ClientCertRequest): Promise<NetworkSettings> {
  return window.ping.network.addCert(request)
}

export function removeClientCert(id: string): Promise<NetworkSettings> {
  return window.ping.network.removeCert(id)
}
