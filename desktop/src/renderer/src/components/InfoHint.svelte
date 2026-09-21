<script lang="ts">
  import type { Snippet } from 'svelte'

  interface Props {
    /** Names what is explained, for the icon: "About runtime variables". */
    label: string
    /** The explanation. A snippet, so a hint can carry markup like `code`. */
    children: Snippet
  }

  let { label, children }: Props = $props()

  let open = $state(false)
</script>

<!--
  A section's description, parked behind its heading. The panel stacks five sections and a
  paragraph under each one pushed the fields off the screen; the text is worth reading once,
  not on every visit. Dismissal mirrors the About popover in the header: focus leaving the
  wrapper closes it.
-->
<span
  class="relative inline-flex"
  onfocusout={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) open = false
  }}
>
  <button
    type="button"
    data-role="info-hint"
    onclick={() => (open = !open)}
    onkeydown={(event) => {
      // The panel closes on Escape, so a hint has to swallow its own.
      if (event.key === 'Escape' && open) {
        open = false
        event.stopPropagation()
      }
    }}
    aria-expanded={open}
    aria-label={label}
    title={label}
    class="rounded-md p-0.5 text-fg-faint transition hover:bg-line/60 hover:text-fg
           {open ? 'bg-line/60 text-fg' : ''}"
  >
    <svg
      viewBox="0 0 24 24"
      class="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5M12 8h.01" />
    </svg>
  </button>
  {#if open}
    <p
      data-role="hint"
      class="absolute left-0 top-full z-30 mt-1 w-52 rounded-lg border border-line bg-panel p-2.5
             text-xs leading-relaxed font-normal normal-case tracking-normal text-fg-muted shadow-lg"
    >
      {@render children()}
    </p>
  {/if}
</span>
