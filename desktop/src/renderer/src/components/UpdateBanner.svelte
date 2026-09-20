<script lang="ts">
  import {
    checkForUpdates,
    dismissUpdate,
    downloadUpdate,
    installUpdate,
    updates
  } from '../lib/updates.svelte'

  interface Props {
    /** True when a close would discard a tab with unsaved changes. */
    hasUnsaved?: boolean
  }

  let { hasUnsaved = false }: Props = $props()

  const current = $derived(updates.state)
  const percent = $derived(Math.round(current.progress?.percent ?? 0))
  const visible = $derived(
    current.enabled && current.status !== 'idle' && updates.dismissed !== current.status
  )

  $effect(() => {
    if (current.status !== 'up-to-date') {
      return
    }
    const timer = window.setTimeout(() => (updates.dismissed = 'up-to-date'), 4000)
    return () => window.clearTimeout(timer)
  })

  const message = $derived.by(() => {
    const version = current.version ?? 'a new version'
    switch (current.status) {
      case 'checking':
        return 'Checking for updates…'
      case 'available':
        return `Ping ${version} is available.`
      case 'downloading':
        return `Downloading Ping ${version}… ${percent}%`
      case 'downloaded':
        return `Ping ${version} is ready to install.`
      case 'installing':
        return 'Restarting to install the update…'
      case 'up-to-date':
        return 'Ping is up to date.'
      case 'error':
        return `Update check failed: ${current.error ?? 'unknown error'}`
      default:
        return ''
    }
  })

  const tone = $derived(
    current.status === 'error'
      ? 'border-amber-900/60 bg-amber-950/30 text-amber-300'
      : current.status === 'available' || current.status === 'downloaded'
        ? 'border-accent/60 bg-accent/10 text-fg'
        : 'border-line bg-panel text-fg-muted'
  )

  function dismiss(): void {
    dismissUpdate()
  }

  function install(): void {
    if (hasUnsaved && !confirm('You have unsaved changes. Restart and install anyway?')) {
      return
    }
    void installUpdate()
  }
</script>

{#if visible}
  <div
    data-role="update-banner"
    data-status={current.status}
    class="flex items-center gap-3 rounded-lg border px-4 py-2 text-sm {tone}"
  >
    <span role="status" class="min-w-0 flex-1 truncate">{message}</span>

    {#if current.status === 'downloading'}
      <span class="h-1.5 w-40 shrink-0 overflow-hidden rounded-full bg-line">
        <span class="block h-full bg-accent transition-all" style="width: {percent}%"></span>
      </span>
    {:else if current.status === 'available'}
      <button
        type="button"
        data-role="update-download"
        onclick={() => void downloadUpdate()}
        class="shrink-0 rounded-md bg-accent px-3 py-1 text-sm font-medium text-white transition
               hover:brightness-110"
      >
        Download
      </button>
      <button
        type="button"
        onclick={dismiss}
        class="shrink-0 rounded-md px-2 py-1 text-sm text-fg-muted transition hover:bg-line/60
               hover:text-fg"
      >
        Later
      </button>
    {:else if current.status === 'downloaded'}
      <button
        type="button"
        data-role="update-install"
        onclick={install}
        class="shrink-0 rounded-md bg-accent px-3 py-1 text-sm font-medium text-white transition
               hover:brightness-110"
      >
        Restart and install
      </button>
      <button
        type="button"
        onclick={dismiss}
        class="shrink-0 rounded-md px-2 py-1 text-sm text-fg-muted transition hover:bg-line/60
               hover:text-fg"
      >
        Later
      </button>
    {:else if current.status === 'error'}
      <button
        type="button"
        data-role="update-retry"
        onclick={() => void checkForUpdates()}
        class="shrink-0 rounded-md px-2 py-1 text-sm text-fg-muted transition hover:bg-line/60
               hover:text-fg"
      >
        Retry
      </button>
      <button
        type="button"
        onclick={dismiss}
        class="shrink-0 rounded-md px-2 py-1 text-sm text-fg-muted transition hover:bg-line/60
               hover:text-fg"
      >
        Dismiss
      </button>
    {:else if current.status === 'up-to-date'}
      <button
        type="button"
        aria-label="Dismiss update notice"
        onclick={dismiss}
        class="shrink-0 rounded px-2 text-lg leading-none text-fg-muted transition hover:text-fg"
      >
        ×
      </button>
    {/if}
  </div>
{/if}
