/**
 * Promise-based confirm dialogs, rendered by `ConfirmDialog.svelte`.
 *
 * `window.confirm` is a native dialog that cannot match the application's design, and
 * `prompt`/`alert` are worse still — so every blocking yes/no question goes through here.
 * Calls are awaited like the native dialog was; while one is on screen the next is queued,
 * so overlapping questions answer in order instead of stacking windows.
 */

interface PendingConfirm {
  /** Each question gets a generation so effects can react even on identical text. */
  id: number
  message: string
  confirmLabel: string
  destructive: boolean
  resolve: (answer: boolean) => void
}

let nextId = 1
const queue: PendingConfirm[] = []

let current = $state<PendingConfirm | null>(null)

/** The question on screen right now, if any. Read by `ConfirmDialog.svelte`. */
export const confirmState = {
  get current(): PendingConfirm | null {
    return current
  }
}

export interface ConfirmOptions {
  /** Label on the confirming button; defaults to "Confirm". */
  confirmLabel?: string
  /** Styles the confirming button in danger tones for destructive actions. */
  destructive?: boolean
}

export function confirmDialog(
  message: string,
  options: ConfirmOptions = {}
): Promise<boolean> {
  return new Promise((resolve) => {
    queue.push({
      id: nextId++,
      message,
      confirmLabel: options.confirmLabel ?? 'Confirm',
      destructive: options.destructive ?? false,
      resolve
    })
    if (!current) {
      showNext()
    }
  })
}

/** Answers the visible question and starts the next one, if queued. */
export function settleConfirm(answer: boolean): void {
  const shown = current
  if (!shown) {
    return
  }
  current = null
  shown.resolve(answer)
  showNext()
}

function showNext(): void {
  current = queue.shift() ?? null
}
