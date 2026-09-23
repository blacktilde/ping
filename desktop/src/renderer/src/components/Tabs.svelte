<script lang="ts">
  import type { Snippet } from 'svelte'

  interface Tab {
    id: string
    label: string
    badge?: string | null
  }

  interface Props {
    tabs: Tab[]
    active?: string
    idPrefix: string
    onSelect?: (id: string) => void
    /** Rendered at the end of the strip, outside the tablist, sharing its row. */
    trailing?: Snippet
  }

  let { tabs, active = $bindable(''), idPrefix, onSelect, trailing }: Props = $props()

  let list = $state<HTMLDivElement>()
  // The underline is one element that slides to the selected tab, rather than a border that
  // blinks from one tab to the next. It stays still until it has been placed once, so the
  // first paint does not show it sweeping in from the left edge.
  let indicator = $state({ left: 0, width: 0 })
  let placed = $state(false)
  // A strip wider than its row fades at the edge, the cue that it scrolls.
  let overflowing = $state(false)

  $effect(() => {
    // Badges change a tab's width, so they are part of what the position depends on.
    void active
    void tabs.map((tab) => `${tab.label}${tab.badge ?? ''}`).join()
    const strip = list
    if (!strip) return

    const measure = (): void => {
      const tab = strip.querySelector<HTMLElement>('[aria-selected="true"]')
      indicator = tab ? { left: tab.offsetLeft, width: tab.offsetWidth } : { left: 0, width: 0 }
      overflowing = strip.scrollWidth > strip.clientWidth + 1
      tab?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
    }
    measure()
    const frame = requestAnimationFrame(() => (placed = true))
    // The web font arriving, or the pane being resized, moves the tabs without a selection.
    const observer = new ResizeObserver(measure)
    observer.observe(strip)
    for (const child of strip.children) observer.observe(child)
    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
    }
  })

  function select(id: string): void {
    active = id
    onSelect?.(id)
  }

  // Roving tabindex: only the selected tab is tabbable, and arrows move between them.
  function onKeydown(event: KeyboardEvent, index: number): void {
    const { key } = event
    let next: number
    if (key === 'ArrowRight' || key === 'ArrowDown') {
      next = (index + 1) % tabs.length
    } else if (key === 'ArrowLeft' || key === 'ArrowUp') {
      next = (index - 1 + tabs.length) % tabs.length
    } else if (key === 'Home') {
      next = 0
    } else if (key === 'End') {
      next = tabs.length - 1
    } else {
      return
    }

    event.preventDefault()
    const target = tabs[next]
    select(target.id)
    document.getElementById(`${idPrefix}-tab-${target.id}`)?.focus()
  }
</script>

<!--
  px-5 is the card inset both the request and the response editor use: a tab is flush with
  its label so the underline is exactly the width of the word, and the strip's rule still
  runs the full width of the card. The rule sits on the wrapper rather than the tablist so
  trailing content can share the row without joining the tablist. When the row is too
  narrow the tabs scroll sideways rather than sliding under the trailing controls.
-->
<div class="flex items-center gap-4 border-b border-line px-5">
  <div
    bind:this={list}
    role="tablist"
    class="scroll-quiet relative flex min-w-0 shrink items-center gap-7 overflow-x-auto"
    style:mask-image={overflowing ? 'linear-gradient(to right, black calc(100% - 2rem), transparent)' : null}
  >
    {#each tabs as tab, index (tab.id)}
      <button
        id={`${idPrefix}-tab-${tab.id}`}
        type="button"
        role="tab"
        aria-selected={active === tab.id}
        aria-controls={`${idPrefix}-panel`}
        tabindex={active === tab.id ? 0 : -1}
        onclick={() => select(tab.id)}
        onkeydown={(event) => onKeydown(event, index)}
        class="flex shrink-0 items-center gap-1.5 whitespace-nowrap py-3.5 text-sm transition-colors
               {active === tab.id ? 'font-medium text-fg' : 'text-fg-muted hover:text-fg'}"
      >
        {tab.label}
        {#if tab.badge}
          <span
            class="tabular rounded-full px-1.5 py-0.5 text-[10px] leading-none transition-colors
                   {active === tab.id ? 'bg-accent/15 text-accent' : 'bg-line text-fg-muted'}"
          >
            {tab.badge}
          </span>
        {/if}
      </button>
    {/each}
    <span
      aria-hidden="true"
      class="pointer-events-none absolute bottom-0 left-0 h-0.5 rounded-full bg-accent
             {placed ? 'transition-[translate,width] duration-200 ease-out' : ''}"
      style="width: {indicator.width}px; translate: {indicator.left}px 0"
    ></span>
  </div>
  {#if trailing}
    <div class="flex flex-1 justify-end">{@render trailing()}</div>
  {/if}
</div>
