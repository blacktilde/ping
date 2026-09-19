/**
 * Copies text out of the sandboxed renderer.
 *
 * The async Clipboard API needs the document focused and can be refused; the textarea
 * fallback still works when it is, at the cost of briefly moving the selection.
 */
export async function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {
      // Fall through to the legacy path.
    }
  }

  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  try {
    area.select()
    if (!document.execCommand('copy')) {
      throw new Error('Clipboard unavailable')
    }
  } finally {
    document.body.removeChild(area)
  }
}
