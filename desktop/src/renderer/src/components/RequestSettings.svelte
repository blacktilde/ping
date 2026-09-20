<script lang="ts">
  import type { HttpVersionPin, RequestDraft, RedirectPolicy } from '../lib/http'
  import { formatBytes } from '../lib/format'

  interface Props {
    draft: RequestDraft
  }

  let { draft }: Props = $props()

  const REDIRECT_POLICIES: { value: RedirectPolicy | ''; label: string }[] = [
    { value: '', label: 'Default (normal)' },
    { value: 'normal', label: 'Normal' },
    { value: 'never', label: 'Never follow' },
    { value: 'always', label: 'Always follow' }
  ]

  const HTTP_VERSIONS: { value: HttpVersionPin | ''; label: string }[] = [
    { value: '', label: 'Default' },
    { value: '1.1', label: 'HTTP/1.1' },
    { value: '2', label: 'HTTP/2 (preferred)' }
  ]

  // Empty inputs mean "use the core's default", so they must not persist as 0.
  function numberValue(field: 'timeoutMs' | 'maxBodyBytes'): string {
    return draft[field] == null ? '' : String(draft[field])
  }

  function setNumber(field: 'timeoutMs' | 'maxBodyBytes', raw: string): void {
    const value = raw.trim()
    if (value === '') {
      delete draft[field]
      return
    }
    const parsed = Number(value)
    if (Number.isFinite(parsed) && parsed >= 0) {
      draft[field] = parsed
    }
  }

  function setTls(checked: boolean): void {
    if (checked) {
      draft.verifyTls = true
    } else {
      delete draft.verifyTls
    }
  }

  function setCookies(checked: boolean): void {
    if (checked) {
      delete draft.cookies
    } else {
      draft.cookies = false
    }
  }

  const bodyCap = $derived(draft.maxBodyBytes == null ? 10 * 1024 * 1024 : draft.maxBodyBytes)
</script>

<div class="h-full overflow-auto p-4">
  <p class="max-w-prose text-xs leading-relaxed text-fg-faint">
    Empty fields use the core's defaults. Settings ride on the request, so they are saved
    with it.
  </p>

  <dl class="mt-4 grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
    <div>
      <label for="setting-timeout" class="block text-xs text-fg-muted">Timeout (ms)</label>
      <input
        id="setting-timeout"
        type="number"
        min="0"
        step="1000"
        value={numberValue('timeoutMs')}
        oninput={(event) => setNumber('timeoutMs', event.currentTarget.value)}
        placeholder="30000"
        class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 font-mono
               outline-none transition focus:border-accent"
      />
    </div>

    <div>
      <label for="setting-redirects" class="block text-xs text-fg-muted">Redirects</label>
      <select
        id="setting-redirects"
        value={draft.redirects ?? ''}
        onchange={(event) => {
          const value = event.currentTarget.value as RedirectPolicy | ''
          if (value === '') {
            delete draft.redirects
          } else {
            draft.redirects = value
          }
        }}
        class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 outline-none
               transition focus:border-accent"
      >
        {#each REDIRECT_POLICIES as policy (policy.value)}
          <option value={policy.value}>{policy.label}</option>
        {/each}
      </select>
    </div>

    <div>
      <label for="setting-http-version" class="block text-xs text-fg-muted">HTTP version</label>
      <select
        id="setting-http-version"
        value={draft.httpVersion ?? ''}
        onchange={(event) => {
          const value = event.currentTarget.value as HttpVersionPin | ''
          if (value === '') {
            delete draft.httpVersion
          } else {
            draft.httpVersion = value
          }
        }}
        class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 outline-none
               transition focus:border-accent"
      >
        {#each HTTP_VERSIONS as version (version.value)}
          <option value={version.value}>{version.label}</option>
        {/each}
      </select>
      <p class="mt-1 text-xs text-fg-faint">
        HTTP/2 is a preference: the server can still answer with 1.1. The response shows which
        version was used.
      </p>
    </div>

    <div>
      <label for="setting-max-body" class="block text-xs text-fg-muted">Response display cap</label>
      <input
        id="setting-max-body"
        type="number"
        min="0"
        step="1024"
        value={numberValue('maxBodyBytes')}
        oninput={(event) => setNumber('maxBodyBytes', event.currentTarget.value)}
        placeholder="10485760"
        class="mt-1 w-full rounded-md border border-line bg-base px-2 py-1.5 font-mono
               outline-none transition focus:border-accent"
      />
      <p class="mt-1 text-xs text-fg-faint">
        How much of a response body is kept for display. Default {formatBytes(10 * 1024 * 1024)};
        raise it here when a large JSON response is cut off.
      </p>
    </div>

    <div>
      <span class="block text-xs text-fg-muted">TLS</span>
      <label class="mt-1 flex items-center gap-2">
        <input
          type="checkbox"
          checked={draft.verifyTls !== false}
          onchange={(event) => setTls(event.currentTarget.checked)}
          class="h-4 w-4 accent-[var(--color-accent)]"
        />
        <span class="text-xs text-fg-muted">
          Verify TLS certificates
          {#if draft.verifyTls === false}
            <span class="text-warning">(verification disabled)</span>
          {/if}
        </span>
      </label>
      <p class="mt-1 text-xs text-fg-faint">
        Turn off only for self-signed certificates you trust; every other request still verifies.
      </p>
    </div>

    <div>
      <span class="block text-xs text-fg-muted">Cookies</span>
      <label class="mt-1 flex items-center gap-2">
        <input
          type="checkbox"
          checked={draft.cookies !== false}
          onchange={(event) => setCookies(event.currentTarget.checked)}
          aria-label="Use the cookie jar"
          class="h-4 w-4 accent-[var(--color-accent)]"
        />
        <span class="text-xs text-fg-muted">
          Use the cookie jar
          {#if draft.cookies === false}
            <span class="text-warning">(this request neither sends nor stores cookies)</span>
          {/if}
        </span>
      </label>
      <p class="mt-1 text-xs text-fg-faint">
        Cookies a response sets are sent back on later requests in the same collection and
        environment. A Cookie header you add yourself replaces the jar's for that request.
      </p>
    </div>
  </dl>

  <p class="mt-4 text-xs text-fg-faint">Current cap: {formatBytes(bodyCap)}</p>
</div>
