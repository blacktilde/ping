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

  const filtered = $derived(
    query.trim()
      ? commands.filter((command) =>
          command.label.toLowerCase().includes(query.trim().toLowerCase())
        )
      : commands
  )

  // Reset and focus each time it opens; queueMicrotask waits for the input to exist.
  $effect(() => {
    if (open) {
      query = ''
      selected = 0
      queueMicrotask(() => input?.focus())
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
        aria-label="Command"
        placeholder="Type a command…"
        spellcheck="false"
        autocomplete="off"
        class="w-full border-b border-line bg-transparent px-4 py-3 text-sm text-fg outline-none"
      />
      <ul class="max-h-80 overflow-auto py-1" role="listbox">
        {#if filtered.length === 0}
          <li class="px-4 py-6 text-center text-sm text-fg-faint">No matching commands.</li>
        {/if}
        {#each filtered as command, index (command.id)}
          <li role="option" aria-selected={index === selected}>
            <button
              type="button"
              onclick={() => run(index)}
              onmouseenter={() => (selected = index)}
              class="flex w-full items-center justify-between px-4 py-2 text-left text-sm
                     transition {index === selected ? 'bg-line text-fg' : 'text-fg-muted'}"
            >
              <span>{command.label}</span>
              {#if command.hint}
                <span class="font-mono text-xs text-fg-faint">{command.hint}</span>
              {/if}
            </button>
          </li>
        {/each}
      </ul>
    </div>
  </div>
{/if}
