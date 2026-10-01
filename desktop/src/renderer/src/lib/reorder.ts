/**
 * Moves the item at `from` so it lands before the item that sits at `before` in the original
 * list (`list.length` means the end). Returns a new array; a move that changes nothing returns
 * an equal one.
 */
export function moveItem<T>(list: readonly T[], from: number, before: number): T[] {
  const result = [...list]
  if (from < 0 || from >= list.length) {
    return result
  }
  const [item] = result.splice(from, 1)
  // Removing the item shifted everything after it one slot left.
  const at = before > from ? before - 1 : before
  result.splice(Math.max(0, Math.min(at, result.length)), 0, item)
  return result
}
