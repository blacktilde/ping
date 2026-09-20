<script lang="ts">
  import { addClientCert, loadNetwork, removeClientCert, saveNetwork } from '../lib/network'
  import type { ClientCertView, ProxyMode } from '../../../shared/network'

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

  // Client certificates are added and removed on the spot: each is a file the user chooses in a
  // dialog, not a field of this form.
  let certs = $state<ClientCertView[]>([])
  let certHost = $state('')
  let certType = $state<'pkcs12' | 'pem'>('pkcs12')
  let certPassphrase = $state('')
  let certError = $state('')
  let adding = $state(false)

  $effect(() => {
    void loadNetwork().then((settings) => {
      mode = settings.proxy.mode
      url = settings.proxy.url
      username = settings.proxy.username
      bypass = settings.proxy.bypass
      hasPassword = settings.proxy.hasPassword
      certs = settings.certs
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
      error = plainError(cause)
    } finally {
      saving = false
    }
  }

  function plainError(cause: unknown): string {
    // Electron prefixes the main-process error; the sentence after it is the useful part.
    const message = cause instanceof Error ? cause.message : String(cause)
    return message.replace(/^Error invoking remote method '[^']*': (Error: )?/, '')
  }

  async function addCert(): Promise<void> {
    certError = ''
    adding = true
    try {
      const before = certs.length
      const settings = await addClientCert({
        host: certHost,
        type: certType,
        passphrase: certPassphrase || undefined
      })
      certs = settings.certs
      if (certs.length > before) {
        certHost = ''
        certPassphrase = ''
      }
    } catch (cause) {
      certError = plainError(cause)
    } finally {
      adding = false
    }
  }

  async function removeCert(id: string): Promise<void> {
    certError = ''
    try {
      certs = (await removeClientCert(id)).certs
    } catch (cause) {
      certError = plainError(cause)
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

      <section data-role="network-certs" class="border-t border-line pt-4">
        <h3 class="text-xs font-medium text-fg-muted">Client certificates</h3>
        <p class="mt-1 text-xs leading-relaxed text-fg-faint">
          For servers that require mutual TLS. A certificate is offered only to the hosts you name,
          never to a host a redirect leads to. The passphrase is stored encrypted and never shown.
        </p>

        {#if certs.length > 0}
          <ul class="mt-2 space-y-1">
            {#each certs as cert (cert.id)}
              <li
                data-role="network-cert"
                class="flex items-center gap-2 rounded-md border border-line px-2 py-1.5 text-xs"
              >
                <span class="font-mono text-fg">{cert.host}</span>
                <span class="min-w-0 flex-1 truncate text-fg-faint">
                  {cert.files.join(' + ')}{cert.hasPassphrase ? ' · passphrase saved' : ''}
                </span>
                <button
                  type="button"
                  data-role="network-cert-remove"
                  aria-label="Remove certificate for {cert.host}"
                  onclick={() => void removeCert(cert.id)}
                  class="rounded px-2 py-0.5 text-fg-muted transition hover:text-danger"
                >
                  Remove
                </button>
              </li>
            {/each}
          </ul>
        {/if}

        <div class="mt-3 grid grid-cols-2 gap-2">
          <input
            data-role="network-cert-host"
            bind:value={certHost}
            aria-label="Certificate host"
            placeholder="api.example.com"
            autocomplete="off"
            spellcheck="false"
            class="rounded-md border border-line bg-base px-2 py-1.5 font-mono text-xs outline-none
                   transition focus:border-accent"
          />
          <select
            data-role="network-cert-type"
            bind:value={certType}
            aria-label="Certificate format"
            class="rounded-md border border-line bg-base px-2 py-1.5 text-xs outline-none
                   transition focus:border-accent"
          >
            <option value="pkcs12">PKCS#12 (.p12, .pfx)</option>
            <option value="pem">PEM certificate + key (PKCS#8)</option>
          </select>
          <input
            data-role="network-cert-passphrase"
            type="password"
            bind:value={certPassphrase}
            aria-label="Certificate passphrase"
            placeholder="Passphrase (if any)"
            autocomplete="off"
            class="rounded-md border border-line bg-base px-2 py-1.5 text-xs outline-none
                   transition focus:border-accent"
          />
          <button
            type="button"
            data-role="network-cert-add"
            disabled={adding || !loaded}
            onclick={() => void addCert()}
            class="rounded-md border border-line px-2 py-1.5 text-xs text-fg-muted transition
                   hover:border-fg-muted hover:text-fg disabled:opacity-50"
          >
            Choose file…
          </button>
        </div>
        {#if certError}
          <p data-role="network-cert-error" role="alert" class="mt-2 text-xs text-danger">{certError}</p>
        {/if}
      </section>

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
