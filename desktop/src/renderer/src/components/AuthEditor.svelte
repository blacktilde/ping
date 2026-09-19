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

  const field =
    'min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none ' +
    'transition focus:border-accent'
</script>

<div class="flex h-full flex-col overflow-auto">
  <div class="flex items-center gap-3 border-b border-line px-4 py-2">
    <select
      bind:value={auth.type}
      aria-label="Auth type"
      class="rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none
             transition focus:border-accent"
    >
      {#each types as option (option.value)}
        <option value={option.value}>{option.label}</option>
      {/each}
    </select>
    <span class="text-xs text-neutral-600">
      Values may use <code class="font-mono">&#123;&#123;name&#125;&#125;</code>; secrets are
      stored by the shell, never in the file.
    </span>
  </div>

  <div class="flex flex-col gap-3 p-4">
    {#if auth.type === 'basic'}
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Username</span>
        <input bind:value={auth.username} aria-label="Username" class={field} />
      </label>
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Password</span>
        <input bind:value={auth.password} aria-label="Password" type="password" class={field} />
      </label>
    {:else if auth.type === 'bearer'}
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Token</span>
        <input bind:value={auth.token} aria-label="Bearer token" class={field} />
      </label>
    {:else if auth.type === 'api-key'}
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Key</span>
        <input bind:value={auth.key} aria-label="API key name" class={field} />
      </label>
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Value</span>
        <input bind:value={auth.value} aria-label="API key value" class={field} />
      </label>
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Add to</span>
        <select
          bind:value={auth.in}
          aria-label="API key location"
          class="rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none
                 transition focus:border-accent"
        >
          <option value="header">Header</option>
          <option value="query">Query parameter</option>
        </select>
      </label>
    {:else if auth.type === 'oauth2-client-credentials' || auth.type === 'oauth2-authorization-code'}
      {#if auth.type === 'oauth2-authorization-code'}
        <label class="flex items-center gap-3">
          <span class="w-28 shrink-0 text-sm text-neutral-500">Authorize URL</span>
          <input bind:value={auth.authUrl} aria-label="Authorization URL" class={field} />
        </label>
      {/if}
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Token URL</span>
        <input bind:value={auth.tokenUrl} aria-label="Token URL" class={field} />
      </label>
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Client ID</span>
        <input bind:value={auth.clientId} aria-label="Client ID" class={field} />
      </label>
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Client secret</span>
        <input bind:value={auth.clientSecret} aria-label="Client secret" class={field} />
      </label>
      <label class="flex items-center gap-3">
        <span class="w-28 shrink-0 text-sm text-neutral-500">Scopes</span>
        <input bind:value={auth.scopes} aria-label="Scopes" class={field} />
      </label>
      {#if auth.type === 'oauth2-authorization-code'}
        <div class="flex items-center gap-3">
          <span class="w-28 shrink-0"></span>
          <button
            type="button"
            onclick={() => onAuthorize?.()}
            class="rounded-lg border border-line px-4 py-2 text-sm text-neutral-300 transition
                   hover:border-accent"
          >
            Authorize
          </button>
          {#if status}
            <span class="text-xs text-neutral-400" data-role="auth-status">{status}</span>
          {/if}
        </div>
      {/if}
    {:else}
      <p class="text-sm text-neutral-600">This request is not authenticated.</p>
    {/if}
  </div>
</div>
