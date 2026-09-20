<script lang="ts">
  interface Command {
    id: string
    label: string
    hint?: string
    run: () => void
  }

  interface Props {
    open?: boolean
    commands: Command[]
  }

  let { open = $bindable(false), commands }: Props = $props()

  let query = $state('')
  let selected = $state(0)
  let input = $state<HTMLInputElement>()
  let returnFocus: HTMLElement | null = null

  /**
   * Subsequence match with a bonus for contiguous runs, so "envnone" finds
   * "Environment: none" and exact prefixes rank first. Not fuzzy in the editor sense —
   * every typed character must appear in order — but forgiving of separators.
   */
  function score(label: string, query: string): number {
    const haystack = label.toLowerCase()
    const needle = query.toLowerCase()
    let index = 0
    let total = 0
    let run = 0
    for (const character of needle) {
      const found = haystack.indexOf(character, index)
      if (found === -1) {
        return -1
      }
      total += found === index ? 2 + run : 1
      run = found === index ? run + 1 : 0
      index = found + 1
    }
    return total
  }

  const filtered = $derived.by(() => {
    const needle = query.trim()
    if (!needle) {
      return commands
    }
    return commands
      .map((command) => ({ command, score: score(command.label, needle) }))
      .filter((entry) => entry.score >= 0)
      .sort((a, b) => b.score - a.score)
      .map((entry) => entry.command)
  })

  // The combobox owns focus: the selected option is announced through
  // aria-activedescendant rather than being focusable itself.
  const activeOption = $derived(
    filtered.length > 0 && selected >= 0 ? `palette-option-${selected}` : undefined
  )

  // Reset on open, and hand focus back to whatever had it on close.
  $effect(() => {
    if (open) {
      returnFocus = document.activeElement as HTMLElement | null
      query = ''
      selected = 0
      queueMicrotask(() => input?.focus())
    } else if (returnFocus) {
      returnFocus.focus()
      returnFocus = null
    }
  })

  $effect(() => {
    if (selected >= filtered.length) {
      selected = Math.max(0, filtered.length - 1)
    }
  })

  function run(index: number): void {
    const command = filtered[index]
    if (!command) {
      return
    }
    open = false
    command.run()
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      open = false
    } else if (event.key === 'Tab') {
      // A modal: keep focus inside rather than walking into the page behind it.
      event.preventDefault()
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      selected = Math.min(selected + 1, filtered.length - 1)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      selected = Math.max(selected - 1, 0)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      run(selected)
    }
  }
</script>

{#if open}
  <div class="fixed inset-0 z-50 flex items-start justify-center bg-black/40 pt-24">
    <button
      type="button"
      aria-label="Close command palette"
      class="absolute inset-0 h-full w-full cursor-default"
      onclick={() => (open = false)}
    ></button>
    <div
      data-role="palette"
      role="dialog"
      aria-modal="true"
      aria-label="Command palette"
      tabindex="-1"
      class="motion-rise relative z-10 w-full max-w-lg overflow-hidden rounded-xl border border-line
             bg-panel shadow-2xl"
    >
      <input
        bind:this={input}
        bind:value={query}
        onkeydown={onKeydown}
        role="combobox"
        aria-expanded={open}
        aria-controls="palette-listbox"
        aria-autocomplete="list"
        aria-activedescendant={activeOption}
        aria-label="Command"
        placeholder="Type a command…"
        spellcheck="false"
        autocomplete="off"
        class="w-full border-b border-line bg-transparent px-4 py-3 text-sm text-fg outline-none"
      />
      <ul id="palette-listbox" class="max-h-80 overflow-auto py-1" role="listbox">
        {#if filtered.length === 0}
          <li role="presentation" class="px-4 py-6 text-center text-sm text-fg-faint">
            No matching commands.
          </li>
        {/if}
        {#each filtered as command, index (command.id)}
          <li role="presentation">
            <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_noninteractive_element_interactions -->
            <div
              id={`palette-option-${index}`}
              role="option"
              tabindex="-1"
              aria-selected={index === selected}
              onclick={() => run(index)}
              onmouseenter={() => (selected = index)}
              class="flex w-full cursor-pointer items-center justify-between px-4 py-2 text-left
                     text-sm transition {index === selected ? 'bg-line text-fg' : 'text-fg-muted'}"
            >
              <span>{command.label}</span>
              {#if command.hint}
                <span class="font-mono text-xs text-fg-faint">{command.hint}</span>
              {/if}
            </div>
          </li>
        {/each}
      </ul>
    </div>
  </div>
{/if}
