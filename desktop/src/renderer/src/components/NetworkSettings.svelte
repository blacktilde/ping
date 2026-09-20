<script lang="ts">
  import { loadNetwork, saveNetwork } from '../lib/network'
  import type { ProxyMode } from '../../../shared/network'

  interface Props {
    onClose: () => void
  }

  let { onClose }: Props = $props()

  const MODES: { value: ProxyMode; label: string }[] = [
    { value: 'none', label: 'No proxy' },
    { value: 'system', label: 'System (environment variables)' },
    { value: 'manual', label: 'Manual' }
  ]

  let mode = $state<ProxyMode>('none')
  let url = $state('')
  let username = $state('')
  let bypass = $state('')
  let hasPassword = $state(false)
  // undefined keeps the stored password; a string replaces it; '' removes it.
  let password = $state('')
  let clearPassword = $state(false)
  let error = $state('')
  let saving = $state(false)
  let loaded = $state(false)

  $effect(() => {
    void loadNetwork().then((settings) => {
      mode = settings.proxy.mode
      url = settings.proxy.url
      username = settings.proxy.username
      bypass = settings.proxy.bypass
      hasPassword = settings.proxy.hasPassword
      loaded = true
    })
  })

  async function save(event: SubmitEvent): Promise<void> {
    event.preventDefault()
    error = ''
    saving = true
    try {
      await saveNetwork({
        proxy: { mode, url, username, bypass },
        password: clearPassword ? '' : password === '' ? undefined : password
      })
      onClose()
    } catch (cause) {
      // Electron prefixes the main-process error; the sentence after it is the useful part.
      const message = cause instanceof Error ? cause.message : String(cause)
      error = message.replace(/^Error invoking remote method '[^']*': (Error: )?/, '')
    } finally {
      saving = false
    }
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
    }
  }
</script>

<svelte:window onkeydown={onKeydown} />

<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
  <button
    type="button"
    aria-label="Dismiss"
    class="absolute inset-0 h-full w-full cursor-default"
    onclick={onClose}
  ></button>
  <div
    data-role="network-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="Network settings"
    tabindex="-1"
    class="motion-rise relative z-10 w-full max-w-lg rounded-xl border border-line bg-panel shadow-2xl"
  >
  <form onsubmit={save}>
    <header class="border-b border-line px-5 py-4">
      <h2 class="text-sm font-medium text-fg">Network settings</h2>
      <p class="mt-1 text-xs leading-relaxed text-fg-faint">
        These belong to you, not to a collection: they apply to every request and run in this app
        and are never saved in a collection file.
      </p>
    </header>

    <div class="space-y-4 px-5 py-4 text-sm">
      <div>
        <label for="network-mode" class="block text-xs text-fg-muted">Proxy</label>
        <select
          id="network-mode"
          data-role="network-mode"
          bind:value={mode}
          disabled={!loaded}
          class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 outline-none
                 transition focus:border-accent"
        >
          {#each MODES as option (option.value)}
            <option value={option.value}>{option.label}</option>
          {/each}
        </select>
      </div>

      {#if mode === 'system'}
        <p class="text-xs leading-relaxed text-fg-faint">
          Uses HTTP_PROXY, HTTPS_PROXY, ALL_PROXY and NO_PROXY from the environment Ping was started
          in. Loopback addresses are proxied too unless NO_PROXY lists them.
        </p>
      {/if}

      {#if mode === 'manual'}
        <div>
          <label for="network-url" class="block text-xs text-fg-muted">Address</label>
          <input
            id="network-url"
            data-role="network-url"
            bind:value={url}
            placeholder="proxy.example.com:8080"
            autocomplete="off"
            spellcheck="false"
            class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 font-mono
                   outline-none transition focus:border-accent"
          />
          <p class="mt-1 text-xs text-fg-faint">
            An HTTP proxy. HTTPS requests are tunnelled through it. SOCKS is not supported.
          </p>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <div>
            <label for="network-username" class="block text-xs text-fg-muted">Username</label>
            <input
              id="network-username"
              data-role="network-username"
              bind:value={username}
              autocomplete="off"
              spellcheck="false"
              class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 outline-none
                     transition focus:border-accent"
            />
          </div>
          <div>
            <label for="network-password" class="block text-xs text-fg-muted">Password</label>
            <input
              id="network-password"
              data-role="network-password"
              type="password"
              bind:value={password}
              disabled={clearPassword}
              placeholder={hasPassword ? '•••••••• (saved)' : ''}
              autocomplete="off"
              class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 outline-none
                     transition focus:border-accent"
            />
            {#if hasPassword}
              <label class="mt-1 flex items-center gap-2 text-xs text-fg-muted">
                <input
                  type="checkbox"
                  bind:checked={clearPassword}
                  class="h-3.5 w-3.5 accent-[var(--color-accent)]"
                />
                Remove the saved password
              </label>
            {/if}
          </div>
        </div>

        <div>
          <label for="network-bypass" class="block text-xs text-fg-muted">Bypass</label>
          <input
            id="network-bypass"
            data-role="network-bypass"
            bind:value={bypass}
            placeholder="localhost, 127.0.0.1, .internal.example.com"
            autocomplete="off"
            spellcheck="false"
            class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 font-mono
                   outline-none transition focus:border-accent"
          />
          <p class="mt-1 text-xs text-fg-faint">
            Hosts that go direct. Loopback is proxied unless it is listed here. The password is
            stored encrypted and is sent to the proxy only.
          </p>
        </div>
      {/if}

      {#if error}
        <p data-role="network-error" role="alert" class="text-xs text-danger">{error}</p>
      {/if}
    </div>

    <footer class="flex items-center justify-end gap-2 border-t border-line px-5 py-3">
      <button
        type="button"
        data-role="network-cancel"
        onclick={onClose}
        class="rounded-lg border border-line px-4 py-2 text-sm text-fg-muted transition
               hover:border-fg-muted hover:text-fg"
      >
        Cancel
      </button>
      <button
        type="submit"
        data-role="network-save"
        disabled={saving || !loaded}
        class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition
               hover:brightness-110 disabled:opacity-50"
      >
        Save
      </button>
    </footer>
  </form>
  </div>
</div>
