<script lang="ts">
  import { variables } from '../lib/vars.svelte'
  import { clearCookies, cookies } from '../lib/cookies.svelte'
  import { clearRuntime, runtime } from '../lib/runtime.svelte'
  import DocsEditor from './DocsEditor.svelte'
  import InfoHint from './InfoHint.svelte'
  import KeyValueEditor from './KeyValueEditor.svelte'
  import SecretsEditor from './SecretsEditor.svelte'

  interface Props {
    onClose: () => void
    /** Saves, and reports whether it worked: only a real save earns the confirmation. */
    onSave: () => Promise<boolean>
    onAddEnvironment: (name: string) => void
    /** Renames the active environment, and reports whether it worked so the field can close. */
    onRenameEnvironment: (path: string, name: string) => Promise<boolean>
    onDeleteEnvironment: (path: string) => void
  }

  let { onClose, onSave, onAddEnvironment, onRenameEnvironment, onDeleteEnvironment }: Props =
    $props()

  // `window.prompt` is not supported in Electron, so naming an environment happens inline,
  // the same way the sidebar names a new collection.
  let naming = $state(false)
  let name = $state('')
  let nameInput = $state<HTMLInputElement>()

  const activeEnvironment = $derived(
    variables.environments.find((entry) => entry.path === variables.environment)
  )

  // Renaming the active environment happens inline too, in place of its name.
  let renaming = $state(false)
  let newName = $state('')
  let renameInput = $state<HTMLInputElement>()

  function startRenaming(): void {
    renaming = true
    newName = activeEnvironment?.name ?? ''
  }

  async function submitRename(event: SubmitEvent): Promise<void> {
    event.preventDefault()
    const trimmed = newName.trim()
    if (!trimmed || !variables.environment) {
      return
    }
    if (trimmed === activeEnvironment?.name || (await onRenameEnvironment(variables.environment, trimmed))) {
      renaming = false
    }
  }

  // The notes are a paragraph of prose in a panel of fields, so they stay folded away until
  // asked for. A collection that has notes shows a dot, or folding them would hide them.
  let showNotes = $state(false)
  const hasNotes = $derived(Boolean(variables.docs?.trim()))

  // Saving is silent otherwise: the file is written somewhere the panel does not show.
  let saved = $state(false)
  let savedTimer: number | undefined

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

  async function save(): Promise<void> {
    if (!(await onSave())) {
      return
    }
    saved = true
    window.clearTimeout(savedTimer)
    savedTimer = window.setTimeout(() => (saved = false), 1800)
  }

  $effect(() => {
    if (naming) {
      queueMicrotask(() => nameInput?.focus())
    }
  })

  $effect(() => {
    if (renaming) {
      queueMicrotask(() => {
        renameInput?.focus()
        renameInput?.select()
      })
    }
  })

  // Switching environments while the field is open would rename the wrong one.
  $effect(() => {
    void variables.environment
    renaming = false
  })

  $effect(() => () => window.clearTimeout(savedTimer))
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
        <InfoHint label="About collection variables">
          Saved with the collection, so everyone who opens the folder gets them. An
          environment of the same name overrides one. Reference one as
          <code class="font-mono">&#123;&#123;name&#125;&#125;</code>.
        </InfoHint>
        <input
          bind:value={variables.name}
          aria-label="Collection name"
          class="min-w-0 flex-1 rounded-md border border-line bg-base px-2 py-1 text-sm
                 outline-none transition focus:border-accent focus:ring-3 focus:ring-accent/15"
        />
        <button
          type="button"
          data-role="toggle-notes"
          onclick={() => (showNotes = !showNotes)}
          aria-expanded={showNotes}
          aria-label="Notes about this collection"
          title="Notes about this collection"
          class="relative shrink-0 rounded-md p-1.5 transition hover:bg-line/60 hover:text-fg
                 {showNotes ? 'bg-line/60 text-fg' : 'text-fg-muted'}"
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
            <path d="M5 4h11l3 3v13H5z" />
            <path d="M8 9h8M8 13h8M8 17h5" />
          </svg>
          {#if hasNotes && !showNotes}
            <span
              data-role="notes-dot"
              class="absolute right-0.5 top-0.5 h-1.5 w-1.5 rounded-full bg-accent"
            ></span>
          {/if}
        </button>
      </div>
      {#if showNotes}
        <div class="h-48 border-t border-line/60">
          {#key variables.collection}
            <DocsEditor
              value={variables.docs}
              label="Collection notes"
              placeholder="What this collection is for, how to authenticate, links…"
              onChange={(value) => (variables.docs = value)}
            />
          {/key}
        </div>
      {/if}
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
        <div class="flex items-center gap-2">
          <h3 class="text-xs uppercase tracking-wide text-fg-muted">Environment</h3>
          <InfoHint label="About environments">
            An environment overrides collection variables while it is selected. Each one is a
            file under <code class="font-mono">environments/</code> in the collection.
          </InfoHint>
        </div>
        {#if naming}
          <form class="flex items-center gap-1" onsubmit={submitName}>
            <input
              bind:this={nameInput}
              bind:value={name}
              aria-label="Environment name"
              placeholder="Environment name"
              class="min-w-0 w-36 rounded-md border border-line bg-base px-2 py-1 text-xs
                     outline-none transition focus:border-accent focus:ring-3 focus:ring-accent/15"
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
        <div data-role="environment-bar" class="flex items-center gap-1 px-3 pb-2">
          {#if renaming}
            <!-- Escape cancels the rename rather than closing the whole panel. -->
            <form
              class="flex min-w-0 flex-1 items-center gap-1"
              onsubmit={(event) => void submitRename(event)}
              onkeydown={(event) => {
                if (event.key === 'Escape') {
                  event.stopPropagation()
                  renaming = false
                }
              }}
            >
              <input
                bind:this={renameInput}
                bind:value={newName}
                aria-label="New environment name"
                class="min-w-0 flex-1 rounded-md border border-line bg-base px-2 py-1 text-sm
                       outline-none transition focus:border-accent focus:ring-3 focus:ring-accent/15"
              />
              <button type="submit" class="rounded-md px-2 py-1 text-xs text-accent">Rename</button>
              <button
                type="button"
                onclick={() => (renaming = false)}
                class="rounded-md px-2 py-1 text-xs text-fg-muted transition hover:text-fg"
              >
                Cancel
              </button>
            </form>
          {:else}
            <span data-role="environment-name" class="min-w-0 flex-1 truncate text-sm text-fg">
              {activeEnvironment?.name ?? ''}
            </span>
            <button
              type="button"
              data-role="rename-environment"
              onclick={startRenaming}
              aria-label="Rename environment"
              title="Rename environment"
              class="shrink-0 rounded-md p-1.5 text-fg-muted transition hover:bg-line/60 hover:text-fg"
            >
              <svg
                viewBox="0 0 24 24"
                class="h-3.5 w-3.5"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="M4 20h4L19 9l-4-4L4 16z" />
                <path d="M13.5 6.5l4 4" />
              </svg>
            </button>
            <button
              type="button"
              data-role="delete-environment"
              onclick={() => onDeleteEnvironment(variables.environment)}
              aria-label="Delete environment"
              title="Delete environment"
              class="shrink-0 rounded-md p-1.5 text-fg-muted transition hover:bg-line/60 hover:text-danger"
            >
              <svg
                viewBox="0 0 24 24"
                class="h-3.5 w-3.5"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
              </svg>
            </button>
          {/if}
        </div>
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
        <p class="px-3 pb-6 text-sm text-fg-faint">No environment selected.</p>
      {/if}
    </section>

    <section data-role="runtime" class="border-t border-line">
      <div class="flex items-center gap-2 px-3 py-2">
        <h3 class="text-xs uppercase tracking-wide text-fg-muted">Runtime</h3>
        <InfoHint label="About runtime variables">
          Captured from responses, kept for this session and never saved. Values are not shown.
        </InfoHint>
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
        <InfoHint label="About cookies">
          Set by responses in this collection and environment, kept for this session and never
          saved. Values are not shown.
        </InfoHint>
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
                class="shrink-0 rounded p-1 text-fg-faint transition hover:text-danger"
              >
                <svg
                  viewBox="0 0 24 24"
                  class="h-3.5 w-3.5"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  aria-hidden="true"
                >
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <section class="border-t border-line">
      <div class="flex items-center gap-2 px-3 py-2">
        <h3 class="text-xs uppercase tracking-wide text-fg-muted">Secrets</h3>
        <InfoHint label="About secrets">
          Encrypted by the shell, never written to the collection. Reference one as
          <code class="font-mono">&#123;&#123;name&#125;&#125;</code>.
        </InfoHint>
      </div>
      <SecretsEditor />
    </section>
  </div>

  <footer class="border-t border-line p-3">
    <button
      type="button"
      onclick={() => void save()}
      disabled={!variables.collection}
      class="w-full rounded-lg border border-accent px-4 py-2 text-sm font-medium text-accent
             transition hover:bg-accent/10 disabled:opacity-40 disabled:hover:bg-transparent"
    >
      <span data-role="save-variables" aria-live="polite" class="flex items-center justify-center gap-1.5">
        {#if saved}
          <svg
            viewBox="0 0 24 24"
            class="h-4 w-4"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M5 12.5l4.5 4.5L19 7" />
          </svg>
          Saved!
        {:else}
          Save variables
        {/if}
      </span>
    </button>
  </footer>
</aside>
