<script lang="ts">
  import type { HttpHeader } from '../lib/http'

  interface Props {
    headers: HttpHeader[]
  }

  let { headers }: Props = $props()
</script>

<div class="h-full overflow-auto">
  {#if headers.length === 0}
    <p class="px-4 py-6 text-sm text-neutral-600">No response headers.</p>
  {:else}
    <table class="w-full table-fixed text-left text-sm">
      <thead class="text-xs uppercase tracking-wide text-neutral-600">
        <tr class="border-b border-line">
          <th class="w-1/3 px-4 py-2 font-medium">Name</th>
          <th class="px-4 py-2 font-medium">Value</th>
        </tr>
      </thead>
      <tbody>
        {#each headers as header, index (`${header.name}-${index}`)}
          <tr class="border-b border-line/50 align-top">
            <th class="break-all px-4 py-2 font-mono font-normal text-neutral-400">{header.name}</th>
            <td class="break-all px-4 py-2 font-mono text-neutral-300">{header.value}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</div>
