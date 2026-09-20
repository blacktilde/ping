/**
 * Promise-based confirm dialogs, rendered by `ConfirmDialog.svelte`, so blocking
 * yes/no questions can match the app's design instead of using the native `confirm`.
 * Overlapping questions queue and answer in order.
 */

interface PendingConfirm {
  message: string
  confirmLabel: string
  destructive: boolean
  resolve: (answer: boolean) => void
}

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
