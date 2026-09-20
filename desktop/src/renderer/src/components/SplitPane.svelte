<script lang="ts">
  import { untrack, type Snippet } from 'svelte'

  interface Props {
    direction?: 'horizontal' | 'vertical'
    unit?: 'fraction' | 'pixels'
    // With `end`, the pixel size belongs to the second pane and the first takes the rest.
    anchor?: 'start' | 'end'
    // Slides the anchored pane (the second for `end`, else the first) shut and unmounts it once
    // it is out of sight. The other pane stays mounted throughout. Pixel splits only.
    collapsed?: boolean
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
    anchor = 'start',
    collapsed = false,
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
  // Dragging toward the anchored pane's far edge grows it, so the deltas flip.
  const sign = $derived(unit === 'pixels' && anchor === 'end' ? -1 : 1)

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
        apply(start + sign * (current - origin))
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
    const decrease = vertical ? 'ArrowUp' : sign < 0 ? 'ArrowRight' : 'ArrowLeft'
    const increase = vertical ? 'ArrowDown' : sign < 0 ? 'ArrowLeft' : 'ArrowRight'
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

  // The anchored pane grows from nothing on open and shrinks to nothing on close. It stays
  // mounted while it slides, then goes, so a closed panel costs nothing and reopening starts fresh.
  const SLIDE_MS = 200
  const anchoredSecond = $derived(unit === 'pixels' && anchor === 'end')
  let rendered = $state(untrack(() => !collapsed))
  let open = $state(untrack(() => !collapsed))

  $effect(() => {
    if (!collapsed) {
      rendered = true
      // Two frames: the first paints the pane at zero width, so the second has something to animate from.
      let second = 0
      const first = requestAnimationFrame(() => {
        second = requestAnimationFrame(() => (open = true))
      })
      return () => {
        cancelAnimationFrame(first)
        cancelAnimationFrame(second)
      }
    }
    open = false
    const timer = setTimeout(() => (rendered = false), SLIDE_MS)
    return () => clearTimeout(timer)
  })

  const fill = 'flex: 1 1 0%'
  const shown = $derived(open ? size : 0)
  const firstStyle = $derived(
    anchoredSecond ? fill : unit === 'fraction' ? `flex: ${size} 1 0%` : `flex: 0 0 ${shown}px`
  )
  const secondStyle = $derived(
    anchoredSecond ? `flex: 0 0 ${shown}px` : unit === 'fraction' ? `flex: ${1 - size} 1 0%` : fill
  )
  const slide = $derived(
    dragging ? '' : 'transition-[flex-basis] duration-200 ease-out motion-reduce:transition-none'
  )
  const valueNow = $derived(Math.round(unit === 'fraction' ? size * 100 : size))
  const valueMin = $derived(Math.round(unit === 'fraction' ? min * 100 : min))
  const valueMax = $derived(Math.round(unit === 'fraction' ? max * 100 : max))
</script>

{#snippet pinned(content: Snippet, toEnd: boolean)}
  <!-- Fixed to the pane's full width, so what slides in and out is clipped, not squeezed. -->
  <div class="h-full {toEnd ? 'justify-self-end' : 'justify-self-start'}" style="width: {size}px">
    {@render content()}
  </div>
{/snippet}

<div
  bind:this={container}
  class="flex min-h-0 min-w-0 flex-1 {vertical ? 'flex-col' : 'flex-row'}"
>
  {#if anchoredSecond || rendered}
    <div
      class="grid min-h-0 min-w-0 grid-cols-1 grid-rows-1 overflow-hidden {anchoredSecond ? '' : slide}"
      style={firstStyle}
    >
      {#if unit === 'pixels' && !anchoredSecond}
        {@render pinned(first, false)}
      {:else}
        {@render first()}
      {/if}
    </div>
  {/if}

  {#if rendered}
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
      style:width={vertical ? undefined : open ? '0.75rem' : '0'}
      class="group flex shrink-0 items-center justify-center overflow-hidden outline-none
             {slide ? 'transition-[width] duration-200 ease-out motion-reduce:transition-none' : ''}
             {vertical ? 'h-3 cursor-row-resize' : 'cursor-col-resize'}"
    >
      <span
        class="rounded-full bg-line transition group-hover:bg-accent group-focus-visible:bg-accent
               {vertical ? 'h-1 w-10' : 'h-10 w-1'}"
      ></span>
    </div>
  {/if}

  {#if !anchoredSecond || rendered}
    <div
      class="grid min-h-0 min-w-0 grid-cols-1 grid-rows-1 overflow-hidden {anchoredSecond ? slide : ''}"
      style={secondStyle}
    >
      {#if anchoredSecond}
        {@render pinned(second, true)}
      {:else}
        {@render second()}
      {/if}
    </div>
  {/if}
</div>
