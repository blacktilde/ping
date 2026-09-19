<script lang="ts">
  import type { Snippet } from 'svelte'

  interface Props {
    direction?: 'horizontal' | 'vertical'
    unit?: 'fraction' | 'pixels'
    initial?: number
    min?: number
    max?: number
    storageKey?: string
    label?: string
    first: Snippet
    second: Snippet
  }

  let {
    direction = 'vertical',
    unit = 'fraction',
    initial = unit === 'fraction' ? 0.5 : 256,
    min = unit === 'fraction' ? 0.15 : 180,
    max = unit === 'fraction' ? 0.85 : 520,
    storageKey,
    label = 'Resize panels',
    first,
    second
  }: Props = $props()

  // A vertical split stacks the panes, so its divider is horizontal to the reader.
  const vertical = $derived(direction === 'vertical')

  function clamp(value: number): number {
    return Math.min(max, Math.max(min, value))
  }

  function stored(): number {
    if (!storageKey) {
      return clamp(initial)
    }
    try {
      const value = Number(localStorage.getItem(storageKey))
      return Number.isFinite(value) && value > 0 ? clamp(value) : clamp(initial)
    } catch {
      return clamp(initial)
    }
  }

  let size = $state(stored())
  let dragging = $state(false)
  let container = $state<HTMLDivElement>()

  function apply(next: number): void {
    size = clamp(next)
    if (!storageKey) {
      return
    }
    try {
      localStorage.setItem(storageKey, String(size))
    } catch {
      // A locked-down profile just means the size is not remembered.
    }
  }

  function onPointerDown(event: PointerEvent): void {
    event.preventDefault()
    const rect = container?.getBoundingClientRect()
    if (!rect) {
      return
    }
    dragging = true
    // Keep receiving moves even if the pointer leaves the window. Synthetic events in tests
    // have no live pointer to capture, which is the only reason this is guarded.
    const handle = event.currentTarget as HTMLElement
    try {
      handle.setPointerCapture(event.pointerId)
    } catch {
      // No active pointer to capture.
    }
    const origin = vertical ? event.clientY : event.clientX
    const start = size

    const move = (moveEvent: PointerEvent): void => {
      const current = vertical ? moveEvent.clientY : moveEvent.clientX
      if (unit === 'pixels') {
        apply(start + (current - origin))
        return
      }
      const bounds = container?.getBoundingClientRect()
      if (!bounds) {
        return
      }
      const position = current - (vertical ? bounds.top : bounds.left)
      apply(position / (vertical ? bounds.height : bounds.width))
    }
    const stop = (): void => {
      dragging = false
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', stop)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', stop)
  }

  function onKeydown(event: KeyboardEvent): void {
    const step = unit === 'fraction' ? (event.shiftKey ? 0.1 : 0.02) : event.shiftKey ? 48 : 16
    const decrease = vertical ? 'ArrowUp' : 'ArrowLeft'
    const increase = vertical ? 'ArrowDown' : 'ArrowRight'
    if (event.key === decrease) {
      apply(size - step)
    } else if (event.key === increase) {
      apply(size + step)
    } else if (event.key === 'Home') {
      apply(min)
    } else if (event.key === 'End') {
      apply(max)
    } else {
      return
    }
    event.preventDefault()
  }

  // Dragging past the divider would otherwise select the text underneath it.
  $effect(() => {
    if (!dragging) {
      return
    }
    const { style } = document.body
    const cursor = style.cursor
    const userSelect = style.userSelect
    style.cursor = vertical ? 'row-resize' : 'col-resize'
    style.userSelect = 'none'
    return () => {
      style.cursor = cursor
      style.userSelect = userSelect
    }
  })

  const firstStyle = $derived(unit === 'fraction' ? `flex: ${size} 1 0%` : `flex: 0 0 ${size}px`)
  const secondStyle = $derived(unit === 'fraction' ? `flex: ${1 - size} 1 0%` : 'flex: 1 1 0%')
  const valueNow = $derived(Math.round(unit === 'fraction' ? size * 100 : size))
  const valueMin = $derived(Math.round(unit === 'fraction' ? min * 100 : min))
  const valueMax = $derived(Math.round(unit === 'fraction' ? max * 100 : max))
</script>

<div
  bind:this={container}
  class="flex min-h-0 min-w-0 flex-1 {vertical ? 'flex-col' : 'flex-row'}"
>
  <div class="grid min-h-0 min-w-0 grid-cols-1 grid-rows-1 overflow-hidden" style={firstStyle}>
    {@render first()}
  </div>

  <!-- A focusable separator is a widget: the ARIA splitter pattern, which the linter misses. -->
  <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
  <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
  <div
    role="separator"
    aria-orientation={vertical ? 'horizontal' : 'vertical'}
    aria-label={label}
    aria-valuenow={valueNow}
    aria-valuemin={valueMin}
    aria-valuemax={valueMax}
    tabindex="0"
    onpointerdown={onPointerDown}
    onkeydown={onKeydown}
    class="group flex shrink-0 items-center justify-center outline-none
           {vertical ? 'h-3 cursor-row-resize' : 'w-3 cursor-col-resize'}"
  >
    <span
      class="rounded-full bg-line transition group-hover:bg-accent group-focus-visible:bg-accent
             {vertical ? 'h-1 w-10' : 'h-10 w-1'}"
    ></span>
  </div>

  <div class="grid min-h-0 min-w-0 grid-cols-1 grid-rows-1 overflow-hidden" style={secondStyle}>
    {@render second()}
  </div>
</div>
