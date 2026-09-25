<script lang="ts" module>
  export interface MenuItem {
    label: string
    run: () => void
    disabled?: boolean
    /** Starts a new group: a rule is drawn above this item. */
    separated?: boolean
    /** A stable hook for the smoke test, which cannot rely on labels staying put. */
    role?: string
  }
</script>

<script lang="ts">
  import { tick } from 'svelte'

  interface Props {
    items: MenuItem[]
    /** Where the pointer was, or the corner of the element the keyboard opened it from. */
    x: number
    y: number
    label: string
    onClose: () => void
  }

  let { items, x, y, label, onClose }: Props = $props()

  let menu = $state<HTMLDivElement>()
  let left = $state(0)
  let top = $state(0)
  // Focus returns here when the menu closes without running anything.
  const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null

  // The top layer, like the variable menu: the tab strip scrolls and clips its overflow, so a
  // menu drawn inside it would be cut off. Placed at the pointer, then nudged back on screen.
  $effect(() => {
    if (!menu) {
      return
    }
    menu.showPopover()
    const { width, height } = menu.getBoundingClientRect()
    left = Math.max(4, Math.min(x, window.innerWidth - width - 4))
    top = Math.max(4, Math.min(y, window.innerHeight - height - 4))
    void tick().then(() => buttons()[0]?.focus())
  })

  function buttons(): HTMLButtonElement[] {
    return [...(menu?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  }

  function close(restoreFocus: boolean): void {
    onClose()
    if (restoreFocus) {
      opener?.focus()
    }
  }

  function choose(item: MenuItem): void {
    close(true)
    item.run()
  }

  function onKeydown(event: KeyboardEvent): void {
    const list = buttons()
    const current = list.indexOf(document.activeElement as HTMLButtonElement)
    let next: number
    if (event.key === 'ArrowDown') {
      next = (current + 1) % list.length
    } else if (event.key === 'ArrowUp') {
      next = (current - 1 + list.length) % list.length
    } else if (event.key === 'Home') {
      next = 0
    } else if (event.key === 'End') {
      next = list.length - 1
    } else if (event.key === 'Escape') {
      event.preventDefault()
      close(true)
      return
    } else if (event.key === 'Tab') {
      event.preventDefault()
      close(true)
      return
    } else {
      return
    }
    event.preventDefault()
    list[next]?.focus()
  }

  // Anything outside the menu dismisses it: a click elsewhere, the window losing focus, a resize
  // that would leave it floating away from the tab it belongs to.
  function onPointerDown(event: PointerEvent): void {
    if (menu && !menu.contains(event.target as Node)) {
      close(false)
    }
  }
</script>

<svelte:window
  onpointerdowncapture={onPointerDown}
  onblur={() => close(false)}
  onresize={() => close(false)}
/>

<div
  bind:this={menu}
  popover="manual"
  role="menu"
  tabindex="-1"
  aria-label={label}
  data-role="context-menu"
  onkeydown={onKeydown}
  oncontextmenu={(event) => event.preventDefault()}
  style:left="{left}px"
  style:top="{top}px"
  class="fixed inset-auto m-0 min-w-52 rounded-lg border border-line bg-panel p-1 text-sm text-fg
         shadow-2xl"
>
  {#each items as item (item.label)}
    {#if item.separated}
      <div role="separator" class="my-1 border-t border-line"></div>
    {/if}
    <button
      type="button"
      role="menuitem"
      data-role={item.role}
      disabled={item.disabled}
      onclick={() => choose(item)}
      class="flex w-full items-center rounded-md px-2.5 py-1.5 text-left transition
             hover:bg-line/60 focus:bg-line/60 focus:outline-none disabled:cursor-default
             disabled:text-fg-faint disabled:hover:bg-transparent"
    >
      {item.label}
    </button>
  {/each}
</div>
