<script lang="ts">
  import { highlight, menu, MENU_ID, optionId, pick } from '../lib/completion.svelte'

  let list: HTMLUListElement

  // The top layer, not an ordinary overlay: the inputs sit in panes that clip their overflow,
  // and a menu drawn inside one would be cut off at the pane's edge.
  $effect(() => {
    if (menu.open && !list.matches(':popover-open')) {
      list.showPopover()
    } else if (!menu.open && list.matches(':popover-open')) {
      list.hidePopover()
    }
  })

  $effect(() => {
    if (menu.open) {
      list.querySelector(`#${optionId(menu.active)}`)?.scrollIntoView({ block: 'nearest' })
    }
  })
</script>

<ul
  bind:this={list}
  id={MENU_ID}
  popover="manual"
  role="listbox"
  aria-label="Variables"
  data-role="variable-menu"
  style:left="{menu.left}px"
  style:top="{menu.top}px"
  style:min-width="{Math.min(Math.max(menu.width, 240), 420)}px"
  class="fixed inset-auto m-0 max-h-64 max-w-md overflow-auto rounded-lg border border-line bg-panel p-1
         text-sm text-fg shadow-2xl"
>
  {#each menu.options as option, index (option.name)}
    <li
      id={optionId(index)}
      role="option"
      aria-selected={index === menu.active}
      data-name={option.name}
      onmousedown={(event) => {
        // Keep focus in the input, whose blur would otherwise close the menu first.
        event.preventDefault()
        pick(index)
      }}
      onmousemove={() => index !== menu.active && highlight(index)}
      class="flex cursor-pointer items-baseline gap-3 rounded-md px-2.5 py-1.5
             {index === menu.active ? 'bg-accent/15' : ''}"
    >
      <span class="shrink-0 font-mono text-fg">{option.name}</span>
      {#if option.value !== undefined}
        <span class="min-w-0 flex-1 truncate font-mono text-xs text-fg-faint">{option.value}</span>
      {:else}
        <span class="flex-1"></span>
      {/if}
      <span class="shrink-0 text-xs text-fg-muted">{option.source}</span>
    </li>
  {/each}
</ul>
