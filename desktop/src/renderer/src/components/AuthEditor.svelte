<script lang="ts">
  import type { AuthDraft, AuthType } from '../lib/http'

  interface Props {
    auth: AuthDraft
    status?: string
    onAuthorize?: () => void
  }

  let { auth, status = '', onAuthorize }: Props = $props()

  const types: { value: AuthType; label: string }[] = [
    { value: 'none', label: 'No auth' },
    { value: 'basic', label: 'Basic' },
    { value: 'bearer', label: 'Bearer token' },
    { value: 'api-key', label: 'API key' },
    { value: 'oauth2-client-credentials', label: 'OAuth2 client credentials' },
    { value: 'oauth2-authorization-code', label: 'OAuth2 authorization code' }
  ]

  /**
   * A field, its label worn inside the box so the scheme and its credentials share one row
   * instead of stacking a label column under the picker.
   *
   * `label` is short because the picker beside it already names the scheme — "Token" under
   * "Bearer token". `aria` is the long name, which is what a screen reader and the smoke
   * test address the input by, and it defaults to the label where the two agree.
   */
  interface Field {
    label: string
    value: string
    set: (next: string) => void
    aria?: string
    /** A credential that should not sit on screen in plain text. */
    secret?: boolean
  }

  const control =
    'shrink-0 rounded-md border border-line bg-base px-2.5 py-1.5 text-sm outline-none ' +
    'transition focus:border-accent'

  /**
   * `min-w-44` is what decides where the row breaks: a flex item never shrinks past it, so
   * the fields that do not fit wrap to the next line at a readable width instead of
   * collapsing into slivers. Only OAuth2 has enough fields to reach a second line.
   */
  const group =
    'flex min-w-44 flex-1 items-center gap-2 rounded-md border border-line bg-base px-2.5 ' +
    'transition focus-within:border-accent'
</script>

<!--
  Two-way binding does not cross a snippet boundary, so a field reports its edits through a
  setter. `auth` is the caller's draft state, mutated in place the way `bind:value` did.
-->
{#snippet field({ label, value, set, aria, secret }: Field)}
  <label class={group}>
    <span class="shrink-0 text-xs text-fg-faint">{label}</span>
    <input
      {value}
      oninput={(event) => set(event.currentTarget.value)}
      aria-label={aria ?? label}
      type={secret ? 'password' : 'text'}
      class="min-w-0 flex-1 bg-transparent py-1.5 text-sm text-fg outline-none"
    />
  </label>
{/snippet}

<div class="flex h-full flex-col overflow-auto">
  <div class="flex flex-wrap items-center gap-2 px-5 py-3">
    <select bind:value={auth.type} aria-label="Auth type" class={control}>
      {#each types as option (option.value)}
        <option value={option.value}>{option.label}</option>
      {/each}
    </select>

    {#if auth.type === 'basic'}
      {@render field({
        label: 'Username',
        value: auth.username,
        set: (next) => (auth.username = next)
      })}
      {@render field({
        label: 'Password',
        value: auth.password,
        set: (next) => (auth.password = next),
        secret: true
      })}
    {:else if auth.type === 'bearer'}
      {@render field({
        label: 'Token',
        aria: 'Bearer token',
        value: auth.token,
        set: (next) => (auth.token = next)
      })}
    {:else if auth.type === 'api-key'}
      {@render field({
        label: 'Key',
        aria: 'API key name',
        value: auth.key,
        set: (next) => (auth.key = next)
      })}
      {@render field({
        label: 'Value',
        aria: 'API key value',
        value: auth.value,
        set: (next) => (auth.value = next)
      })}
      <select bind:value={auth.in} aria-label="API key location" class={control}>
        <option value="header">Header</option>
        <option value="query">Query parameter</option>
      </select>
    {:else if auth.type === 'oauth2-client-credentials' || auth.type === 'oauth2-authorization-code'}
      {#if auth.type === 'oauth2-authorization-code'}
        {@render field({
          label: 'Authorize URL',
          aria: 'Authorization URL',
          value: auth.authUrl,
          set: (next) => (auth.authUrl = next)
        })}
      {/if}
      {@render field({
        label: 'Token URL',
        value: auth.tokenUrl,
        set: (next) => (auth.tokenUrl = next)
      })}
      {@render field({
        label: 'Client ID',
        value: auth.clientId,
        set: (next) => (auth.clientId = next)
      })}
      {@render field({
        label: 'Client secret',
        value: auth.clientSecret,
        set: (next) => (auth.clientSecret = next)
      })}
      {@render field({ label: 'Scopes', value: auth.scopes, set: (next) => (auth.scopes = next) })}
      {#if auth.type === 'oauth2-authorization-code'}
        <button
          type="button"
          onclick={() => onAuthorize?.()}
          class="shrink-0 rounded-md border border-line px-4 py-1.5 text-sm text-fg transition
                 hover:border-accent"
        >
          Authorize
        </button>
        {#if status}
          <span class="text-xs text-fg-muted" data-role="auth-status">{status}</span>
        {/if}
      {/if}
    {:else}
      <span class="text-sm text-fg-faint">This request is not authenticated.</span>
    {/if}
  </div>
</div>
