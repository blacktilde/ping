<script lang="ts">
  import { confirmState, settleConfirm } from '../lib/confirm.svelte'

  let confirmButton = $state<HTMLButtonElement>()

  // The confirming action is the expected answer, so focus lands there first.
  $effect(() => {
    if (confirmState.current) {
      queueMicrotask(() => confirmButton?.focus())
    }
  })

  function onKeydown(event: KeyboardEvent): void {
    if (!confirmState.current) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      settleConfirm(false)
    } else if (event.key === 'Tab') {
      // A modal: keep focus inside rather than walking into the page behind it.
      event.preventDefault()
    } else if (event.key === 'Enter') {
      event.preventDefault()
      settleConfirm(true)
    }
  }
</script>

<svelte:window onkeydown={onKeydown} />

{#if confirmState.current}
  {@const shown = confirmState.current}
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
    <button
      type="button"
      aria-label="Dismiss"
      class="absolute inset-0 h-full w-full cursor-default"
      onclick={() => settleConfirm(false)}
    ></button>
    <div
      data-role="confirm-dialog"
      role="alertdialog"
      aria-modal="true"
      aria-label="Confirm"
      tabindex="-1"
      class="motion-rise relative z-10 w-full max-w-md rounded-xl border border-line bg-panel
             shadow-2xl"
    >
      <p class="border-b border-line px-5 py-4 text-sm leading-relaxed text-fg">
        {shown.message}
      </p>
      <div class="flex items-center justify-end gap-2 px-5 py-3">
        <button
          type="button"
          data-role="confirm-cancel"
          onclick={() => settleConfirm(false)}
          class="rounded-lg border border-line px-4 py-2 text-sm text-fg-muted transition
                 hover:border-fg-muted hover:text-fg"
        >
          Cancel
        </button>
        <button
          bind:this={confirmButton}
          type="button"
          data-role="confirm-accept"
          data-destructive={shown.destructive}
          onclick={() => settleConfirm(true)}
          class="rounded-lg px-4 py-2 text-sm font-medium text-white transition
                 {shown.destructive ? 'bg-danger hover:brightness-110' : 'bg-accent hover:brightness-110'}"
        >
          {shown.confirmLabel}
        </button>
      </div>
    </div>
  </div>
{/if}
