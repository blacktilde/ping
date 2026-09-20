<script lang="ts">
  import { variables } from '../lib/vars.svelte'
  import { clearCookies, cookies } from '../lib/cookies.svelte'
  import { clearRuntime, runtime } from '../lib/runtime.svelte'
  import DocsEditor from './DocsEditor.svelte'
  import KeyValueEditor from './KeyValueEditor.svelte'
  import SecretsEditor from './SecretsEditor.svelte'

  interface Props {
    onClose: () => void
    onSave: () => void
    onAddEnvironment: (name: string) => void
  }

  let { onClose, onSave, onAddEnvironment }: Props = $props()

  // `window.prompt` is not supported in Electron, so naming an environment happens inline,
  // the same way the sidebar names a new collection.
  let naming = $state(false)
  let name = $state('')
  let nameInput = $state<HTMLInputElement>()

  function startNaming(): void {
    naming = true
    name = ''
  }

  function submitName(event: SubmitEvent): void {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      return
    }
    naming = false
    onAddEnvironment(trimmed)
  }

  $effect(() => {
    if (naming) {
      queueMicrotask(() => nameInput?.focus())
    }
  })
</script>

<!-- Docked on the right in a SplitPane, the mirror of the sidebar: same chrome, other edge. -->
<!-- Escape closes it from anywhere inside; the listener bubbles from the fields. -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<aside
  data-role="variables"
  aria-label="Variables"
  onkeydown={(event) => {
    if (event.key === 'Escape') onClose()
  }}
  class="flex h-full w-full flex-col border-l border-line bg-panel"
>
  <div class="flex items-center justify-between border-b border-line px-3 py-2">
    <span class="rounded-md bg-line px-2 py-1 text-xs font-medium uppercase tracking-wide text-fg">
      Variables
    </span>
    <button
      type="button"
      onclick={onClose}
      aria-label="Close variables"
      title="Close variables"
      class="rounded-md p-1.5 text-fg-muted transition hover:bg-line/60 hover:text-fg"
    >
      <svg
        viewBox="0 0 24 24"
        class="h-4 w-4"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M6 6l12 12M18 6L6 18" />
      </svg>
    </button>
  </div>

  <div class="flex-1 overflow-auto">
    <section class="border-b border-line">
      <div class="flex items-center gap-2 px-3 py-2">
        <h3 class="text-xs uppercase tracking-wide text-fg-muted">Collection</h3>
        <input
          bind:value={variables.name}
          aria-label="Collection name"
          class="min-w-0 flex-1 rounded-md border border-line bg-base px-2 py-1 text-sm
                 outline-none transition focus:border-accent"
        />
      </div>
      <div class="flex h-48 flex-col border-t border-line/60">
        <p class="shrink-0 px-3 pt-2 text-xs text-fg-faint">Notes about this collection</p>
        <div class="min-h-0 flex-1">
          {#key variables.collection}
            <DocsEditor
              value={variables.docs}
              label="Collection notes"
              placeholder="What this collection is for, how to authenticate, links…"
              onChange={(value) => (variables.docs = value)}
            />
          {/key}
        </div>
      </div>
      <div class="border-t border-line/60">
        <KeyValueEditor
          items={variables.collectionVariables}
          nameLabel="Variable name"
          valueLabel="Variable value"
          addLabel="Add variable"
          emptyText="No collection variables."
        />
      </div>
    </section>

    <section>
      <div class="flex items-center justify-between px-3 py-2">
        <h3 class="text-xs uppercase tracking-wide text-fg-muted">Environment</h3>
        {#if naming}
          <form class="flex items-center gap-1" onsubmit={submitName}>
            <input
              bind:this={nameInput}
              bind:value={name}
              aria-label="Environment name"
              placeholder="Environment name"
              class="min-w-0 w-36 rounded-md border border-line bg-base px-2 py-1 text-xs
                     outline-none transition focus:border-accent"
            />
            <button type="submit" class="rounded-md px-2 py-1 text-xs text-accent">Create</button>
          </form>
        {:else}
          <button
            type="button"
            onclick={startNaming}
            class="rounded-md px-2 py-1 text-xs text-fg-muted transition hover:bg-line/60
                   hover:text-fg"
          >
            + New
          </button>
        {/if}
      </div>
      {#if variables.environment}
        <div>
          <KeyValueEditor
            items={variables.environmentVariables}
            nameLabel="Variable name"
            valueLabel="Variable value"
            addLabel="Add variable"
            emptyText="This environment has no variables."
          />
        </div>
      {:else}
        <p class="px-3 pb-6 text-sm text-fg-faint">
          No environment selected. Choose one, or create a new one, to override collection
          variables.
        </p>
      {/if}
    </section>

    <section data-role="runtime" class="border-t border-line">
      <div class="flex items-center gap-2 px-3 py-2">
        <h3 class="text-xs uppercase tracking-wide text-fg-muted">Runtime</h3>
        {#if runtime.names.length > 0}
          <button
            type="button"
            onclick={() => void clearRuntime()}
            aria-label="Clear runtime variables"
            class="ml-auto rounded-md px-2 py-1 text-xs text-fg-muted transition hover:bg-line/60
                   hover:text-fg"
          >
            Clear
          </button>
        {/if}
      </div>
      <p class="px-3 pb-2 text-xs text-fg-faint">
        Captured from responses, kept for this session and never saved. Values are not shown.
      </p>
      {#if runtime.names.length === 0}
        <p class="px-3 pb-3 text-sm text-fg-faint">Nothing captured yet.</p>
      {:else}
        <ul class="pb-2">
          {#each runtime.names as name (name)}
            <li data-role="runtime-name" class="px-3 py-1 font-mono text-sm text-fg-muted">
              {name}
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <section data-role="cookies" class="border-t border-line">
      <div class="flex items-center gap-2 px-3 py-2">
        <h3 class="text-xs uppercase tracking-wide text-fg-muted">Cookies</h3>
        {#if cookies.items.length > 0}
          <button
            type="button"
            onclick={() => void clearCookies(variables.collection, variables.environment)}
            aria-label="Clear cookies"
            class="ml-auto rounded-md px-2 py-1 text-xs text-fg-muted transition hover:bg-line/60
                   hover:text-fg"
          >
            Clear
          </button>
        {/if}
      </div>
      <p class="px-3 pb-2 text-xs text-fg-faint">
        Set by responses in this collection and environment, kept for this session and never
        saved. Values are not shown.
      </p>
      {#if cookies.items.length === 0}
        <p class="px-3 pb-3 text-sm text-fg-faint">No cookies yet.</p>
      {:else}
        <ul class="pb-2">
          {#each cookies.items as cookie (`${cookie.domain}|${cookie.path}|${cookie.name}`)}
            <li data-role="cookie" class="flex items-baseline gap-2 px-3 py-1 text-sm">
              <span class="font-mono text-fg-muted">{cookie.name}</span>
              <span class="min-w-0 truncate text-xs text-fg-faint">
                {cookie.domain}{cookie.path}
              </span>
              <span class="ml-auto shrink-0 text-[10px] uppercase tracking-wide text-fg-faint">
                {cookie.expiresAt ? 'expires' : 'session'}{cookie.secure ? ' · secure' : ''}{cookie.httpOnly
                  ? ' · httponly'
                  : ''}
              </span>
              <button
                type="button"
                onclick={() =>
                  void clearCookies(variables.collection, variables.environment, cookie.domain, cookie.name)}
                aria-label="Delete cookie {cookie.name}"
                class="shrink-0 rounded px-1 text-fg-faint transition hover:text-danger"
              >
                ×
              </button>
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <section class="border-t border-line">
      <div class="px-3 py-2">
        <h3 class="text-xs uppercase tracking-wide text-fg-muted">Secrets</h3>
        <p class="mt-1 text-xs text-fg-faint">
          Encrypted by the shell, never written to the collection. Reference one as
          <code class="font-mono">&#123;&#123;name&#125;&#125;</code>.
        </p>
      </div>
      <SecretsEditor />
    </section>
  </div>

  <footer class="border-t border-line p-3">
    <button
      type="button"
      onclick={onSave}
      disabled={!variables.collection}
      class="w-full rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition
             hover:brightness-110 disabled:opacity-40"
    >
      Save variables
    </button>
  </footer>
</aside>
