<script lang="ts">
  import type { Cookie } from '../lib/response'

  interface Props {
    cookies: Cookie[]
  }

  let { cookies }: Props = $props()

  function flags(cookie: Cookie): string {
    return (
      [
        cookie.httpOnly && 'HttpOnly',
        cookie.secure && 'Secure',
        cookie.sameSite && `SameSite=${cookie.sameSite}`
      ]
        .filter(Boolean)
        .join(', ') || '—'
    )
  }

  function expiry(cookie: Cookie): string {
    if (cookie.expires) return cookie.expires
    return cookie.maxAge ? `max-age=${cookie.maxAge}` : 'session'
  }
</script>

<div class="h-full overflow-auto">
  {#if cookies.length === 0}
    <p class="px-4 py-6 text-sm text-fg-faint">No cookies were set.</p>
  {:else}
    <table class="w-full table-fixed text-left text-sm">
      <thead class="text-xs uppercase tracking-wide text-fg-faint">
        <tr class="border-b border-line">
          <th class="w-1/4 px-4 py-2 font-medium">Name</th>
          <th class="w-1/4 px-4 py-2 font-medium">Value</th>
          <th class="w-1/4 px-4 py-2 font-medium">Domain / Path</th>
          <th class="w-1/4 px-4 py-2 font-medium">Expires / Flags</th>
        </tr>
      </thead>
      <tbody>
        {#each cookies as cookie, index (index)}
          <tr class="border-b border-line/50 align-top">
            <td class="break-all px-4 py-2 font-mono text-fg-muted">{cookie.name}</td>
            <td class="break-all px-4 py-2 font-mono text-fg">{cookie.value}</td>
            <td class="break-all px-4 py-2 font-mono text-fg-muted">
              {cookie.domain ?? '—'}<br />{cookie.path ?? '—'}
            </td>
            <td class="break-words px-4 py-2 text-fg-muted">
              {expiry(cookie)}<br />{flags(cookie)}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</div>
