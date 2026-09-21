<script lang="ts">
  import {
    checkForUpdates,
    clearRequested,
    downloadUpdate,
    installUpdate,
    updates
  } from '../lib/updates.svelte'
  import { confirmDialog } from '../lib/confirm.svelte'

  interface Props {
    /** True when a restart would discard a tab with unsaved changes. */
    hasUnsaved?: boolean
  }

  let { hasUnsaved = false }: Props = $props()

  let open = $state(false)

  const current = $derived(updates.state)
  const percent = $derived(Math.round(current.progress?.percent ?? 0))

  /**
   * States that say nothing the user did not ask about. A launch check that finds nothing
   * stays silent; the same outcome after a command-palette check is worth a moment's badge.
   */
  const transient = $derived(
    current.status === 'checking' || current.status === 'up-to-date' || current.status === 'error'
  )
  const actionable = $derived(
    current.status === 'available' ||
      current.status === 'downloading' ||
      current.status === 'downloaded'
  )
  /** Error is interactive too: it is the one transient state with somewhere to go. */
  const interactive = $derived(actionable || current.status === 'error')
  const visible = $derived(
    current.enabled &&
      (actionable || current.status === 'installing' || (transient && updates.requested))
  )

  // A requested check that turns into a real offer has answered itself: the badge stays on its
  // own merit from here, so the flag stops holding the transient states open behind it.
  $effect(() => {
    if (actionable || current.status === 'installing') {
      clearRequested()
    }
  })

  // "Up to date" is an acknowledgement, not a notice. It fades on its own; an error waits to
  // be read.
  $effect(() => {
    if (current.status !== 'up-to-date' || !updates.requested) {
      return
    }
    const timer = window.setTimeout(clearRequested, 3000)
    return () => window.clearTimeout(timer)
  })

  // Nothing left to act on: a check that restarted, an install under way, a badge that went
  // quiet. The popover has no content for any of them.
  $effect(() => {
    if (!interactive) {
      open = false
    }
  })

  const label = $derived.by(() => {
    switch (current.status) {
      case 'checking':
        return 'Checking…'
      case 'available':
        return 'Update'
      case 'downloading':
        return `Updating ${percent}%`
      case 'downloaded':
        return 'Restart to update'
      case 'installing':
        return 'Restarting…'
      case 'up-to-date':
        return 'Up to date'
      case 'error':
        return 'Update failed'
      default:
        return ''
    }
  })

  const message = $derived.by(() => {
    const version = current.version ?? 'a new version'
    switch (current.status) {
      case 'available':
        return `Ping ${version} is available.`
      case 'downloading':
        return `Downloading Ping ${version}…`
      case 'downloaded':
        return `Ping ${version} is ready to install.`
      case 'error':
        return `Update check failed: ${current.error ?? 'unknown error'}`
      default:
        return label
    }
  })

  const tone = $derived(
    current.status === 'error'
      ? 'border-warning-soft bg-warning-soft text-warning'
      : current.status === 'available' || current.status === 'downloaded'
        ? 'border-accent/40 bg-accent/10 text-accent'
        : 'border-line bg-panel text-fg-muted'
  )

  function dismissError(): void {
    open = false
    clearRequested()
  }

  async function install(): Promise<void> {
    if (hasUnsaved) {
      const proceed = await confirmDialog('You have unsaved changes. Restart and install anyway?', {
        confirmLabel: 'Restart & install',
        destructive: true
      })
      if (!proceed) {
        return
      }
    }
    open = false
    void installUpdate()
  }
</script>

<span class="sr-only" role="status">{visible ? message : ''}</span>

{#if visible}
  <div
    class="relative shrink-0"
    onfocusout={(event) => {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) open = false
    }}
  >
    {#if interactive}
      <button
        type="button"
        data-role="update-badge"
        data-status={current.status}
        onclick={() => (open = !open)}
        aria-expanded={open}
        aria-label={message}
        title={message}
        class="flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs font-medium
               transition hover:brightness-125 {tone}"
      >
        {#if current.status === 'available' || current.status === 'downloaded'}
          <span class="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true"></span>
        {/if}
        {label}
      </button>
    {:else}
      <span
        data-role="update-badge"
        data-status={current.status}
        class="flex items-center rounded-md border px-2 py-1 text-xs {tone}"
      >
        {label}
      </span>
    {/if}

    {#if open}
      <div
        data-role="update-popover"
        class="absolute right-0 top-full z-30 mt-1 w-64 space-y-3 rounded-lg border border-line
               bg-panel p-3 text-xs text-fg-muted shadow-lg"
      >
        <p class="text-fg">{message}</p>

        {#if current.status === 'downloading'}
          <div class="flex items-center gap-2">
            <span class="h-1.5 flex-1 overflow-hidden rounded-full bg-line">
              <span class="block h-full bg-accent transition-all" style="width: {percent}%"></span>
            </span>
            <span class="shrink-0 tabular-nums">{percent}%</span>
          </div>
        {:else}
          <div class="flex items-center justify-end gap-2">
            {#if current.status === 'available'}
              <button
                type="button"
                onclick={() => (open = false)}
                class="rounded-md px-2 py-1 transition hover:bg-line/60 hover:text-fg"
              >
                Later
              </button>
              <button
                type="button"
                data-role="update-download"
                onclick={() => void downloadUpdate()}
                class="rounded-md bg-accent px-3 py-1 font-medium text-white transition
                       hover:brightness-110"
              >
                Download
              </button>
            {:else if current.status === 'downloaded'}
              <button
                type="button"
                onclick={() => (open = false)}
                class="rounded-md px-2 py-1 transition hover:bg-line/60 hover:text-fg"
              >
                Later
              </button>
              <button
                type="button"
                data-role="update-install"
                onclick={install}
                class="rounded-md bg-accent px-3 py-1 font-medium text-white transition
                       hover:brightness-110"
              >
                Restart and install
              </button>
            {:else if current.status === 'error'}
              <button
                type="button"
                onclick={dismissError}
                class="rounded-md px-2 py-1 transition hover:bg-line/60 hover:text-fg"
              >
                Dismiss
              </button>
              <button
                type="button"
                data-role="update-retry"
                onclick={() => void checkForUpdates()}
                class="rounded-md bg-accent px-3 py-1 font-medium text-white transition
                       hover:brightness-110"
              >
                Retry
              </button>
            {/if}
          </div>
        {/if}
      </div>
    {/if}
  </div>
{/if}
