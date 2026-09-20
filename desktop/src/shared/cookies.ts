/**
 * A stored cookie as the UI may see it.
 *
 * The value is a session credential and never leaves the core, so it is not part of this shape.
 */
export interface CookieView {
  name: string
  domain: string
  path: string
  hostOnly: boolean
  secure: boolean
  httpOnly: boolean
  sameSite?: 'Lax' | 'Strict' | 'None'
  /** Epoch millis; absent for a session cookie. */
  expiresAt?: number
}
