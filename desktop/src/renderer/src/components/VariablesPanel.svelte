<script lang="ts">
  import { variables } from '../lib/vars.svelte'
  import KeyValueEditor from './KeyValueEditor.svelte'
  import SecretsEditor from './SecretsEditor.svelte'

  interface Props {
    onClose: () => void
    onSave: () => void
    onAddEnvironment: () => void
  }

  let { onClose, onSave, onAddEnvironment }: Props = $props()
</script>

<aside
  data-role="variables"
  class="flex h-full w-96 shrink-0 flex-col border-l border-line bg-panel"
>
  <header class="flex items-center justify-between border-b border-line px-3 py-2">
    <span class="text-xs font-medium uppercase tracking-wide text-fg-muted">Variables</span>
    <button
      type="button"
      onclick={onClose}
      aria-label="Close variables"
      class="rounded px-2 text-lg leading-none text-fg-muted transition hover:text-fg"
    >
      ×
    </button>
  </header>

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
      <div class="h-52">
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
        <button
          type="button"
          onclick={onAddEnvironment}
          class="rounded-md px-2 py-1 text-xs text-fg-muted transition hover:bg-line/60
                 hover:text-fg"
        >
          + New
        </button>
      </div>
      {#if variables.environment}
        <div class="h-52">
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
