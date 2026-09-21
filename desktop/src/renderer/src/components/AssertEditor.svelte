<script lang="ts">
  import type { Assert, AssertType } from '../lib/http'
  import {
    ASSERT_OPS,
    ASSERT_TYPES,
    assertUsesExpected,
    assertUsesTarget,
    emptyAssert
  } from '../lib/request'

  interface Props {
    items: Assert[]
  }

  let { items }: Props = $props()

  const fieldClass =
    'min-w-0 flex-1 rounded-md border border-line bg-base px-3 py-1.5 text-sm outline-none ' +
    'transition focus:border-accent disabled:opacity-40'
  const selectClass =
    'rounded-md border border-line bg-base px-2 py-1.5 text-sm outline-none transition ' +
    'focus:border-accent'

  /** Each type accepts its own ops; switching type snaps to that type's default. */
  function changeType(item: Assert, type: AssertType): void {
    item.type = type
    item.op = ASSERT_OPS[type][0].value
    if (type === 'status' && !item.expected) item.expected = '200'
  }

  function targetPlaceholder(type: AssertType): string {
    return type === 'header' ? 'Header name' : '$.path.to.value'
  }

  function expectedPlaceholder(type: AssertType): string {
    return type === 'duration' ? 'Max ms' : type === 'status' ? '200' : 'Expected'
  }
</script>

<div class="flex h-full flex-col">
  <div class="flex-1 overflow-auto">
    {#if items.length === 0}
      <p class="py-6 text-sm text-fg-faint">
        No assertions. Add one to check status, headers, JSON values, body text or timing on
        every send.
      </p>
    {/if}

    {#each items as item, index (item)}
      <div class="flex items-center gap-2 border-b border-line/60 py-2">
        <input
          type="checkbox"
          checked={item.enabled !== false}
          onchange={(event) => (item.enabled = event.currentTarget.checked)}
          aria-label="Enable assertion"
          class="accent-accent"
        />
        <select
          value={item.type}
          onchange={(event) => changeType(item, event.currentTarget.value as AssertType)}
          aria-label="Assertion type"
          class={selectClass}
        >
          {#each ASSERT_TYPES as option (option.value)}
            <option value={option.value}>{option.label}</option>
          {/each}
        </select>
        {#if assertUsesTarget(item.type)}
          <input
            bind:value={item.target}
            aria-label="Assertion target"
            placeholder={targetPlaceholder(item.type)}
            class={fieldClass}
          />
        {/if}
        <select
          bind:value={item.op}
          aria-label="Assertion operator"
          class={selectClass}
          disabled={ASSERT_OPS[item.type].length === 1}
        >
          {#each ASSERT_OPS[item.type] as option (option.value)}
            <option value={option.value}>{option.label}</option>
          {/each}
        </select>
        {#if assertUsesExpected(item.type, item.op)}
          <input
            bind:value={item.expected}
            aria-label="Assertion expected value"
            placeholder={expectedPlaceholder(item.type)}
            class={fieldClass}
          />
        {:else}
          <span class="flex-1"></span>
        {/if}
        <button
          type="button"
          onclick={() => items.splice(index, 1)}
          aria-label="Remove assertion"
          class="rounded-md px-2 py-1 text-lg leading-none text-fg-faint transition
                 hover:text-fg"
        >
          ×
        </button>
      </div>
    {/each}
  </div>

  <div class="border-t border-line py-3">
    <button
      type="button"
      onclick={() => items.push(emptyAssert())}
      class="text-sm font-medium text-accent transition hover:brightness-125"
    >
      + Add assertion
    </button>
  </div>
</div>
