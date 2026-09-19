import type { PingApi } from '../../preload/index'

declare global {
  interface Window {
    ping: PingApi
  }
}

export {}
