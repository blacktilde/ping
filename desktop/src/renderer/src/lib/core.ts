/**
 * Access to the core that preserves the JSON-RPC error code.
 *
 * The preload returns an envelope because contextBridge cannot carry custom Error
 * properties. This module is the one place that turns the envelope back into a throw, in
 * the renderer's own realm where `code` survives.
 */

/** Application error codes from `contract/README.md`. */
export const RpcError = {
  requestCancelled: -32001,
  requestFailed: -32002
} as const

export class CoreError extends Error {
  readonly code: number | null

  constructor(code: number | null, message: string) {
    super(message)
    this.name = 'CoreError'
    this.code = code
  }
}

/** Calls a core method and unwraps its envelope, throwing {@link CoreError} on failure. */
export async function call<T>(method: string, params?: unknown): Promise<T> {
  const result = await window.ping.request<T>(method, params)
  if (result.ok) {
    return result.value
  }
  throw new CoreError(result.error.code, result.error.message)
}
