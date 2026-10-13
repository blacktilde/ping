import { readFileSync } from 'node:fs'

/**
 * Pure and free of Electron and of relative imports, so `npm test` can load it. The main process
 * does the writing (atomically) and supplies the display geometry.
 */

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

export interface WindowState {
  width: number
  height: number
  /** Absent when the position was never saved, or is no longer on a connected display. */
  x?: number
  y?: number
  maximized: boolean
}

/** Read the saved state, or null when there is none or the file is unusable. */
export function readWindowState(file: string): WindowState | null {
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8')) as Record<string, unknown> | null
    if (!parsed || typeof parsed !== 'object') {
      return null
    }
    const { width, height, x, y, maximized } = parsed
    if (!isLength(width) || !isLength(height)) {
      return null
    }
    const state: WindowState = { width, height, maximized: maximized === true }
    if (isCoordinate(x) && isCoordinate(y)) {
      state.x = x
      state.y = y
    }
    return state
  } catch {
    return null
  }
}

/**
 * What to hand `BrowserWindow`: the saved size, kept above the window's minimum, and the saved
 * position only while the window would still be visible on a connected display (a monitor may
 * have been unplugged since). Without a usable position the OS places it.
 */
export function restoreBounds(
  saved: WindowState | null,
  fallback: { width: number; height: number },
  min: { width: number; height: number },
  workAreas: Rect[]
): { width: number; height: number; x?: number; y?: number } {
  if (!saved) {
    return { ...fallback }
  }
  const width = Math.max(saved.width, min.width)
  const height = Math.max(saved.height, min.height)
  if (saved.x === undefined || saved.y === undefined) {
    return { width, height }
  }
  const { x, y } = saved
  const visible = workAreas.some(
    (area) =>
      x < area.x + area.width &&
      x + width > area.x &&
      y < area.y + area.height &&
      y + height > area.y
  )
  return visible ? { width, height, x, y } : { width, height }
}

function isLength(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 && value < 100_000
}

function isCoordinate(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) < 1_000_000
}
