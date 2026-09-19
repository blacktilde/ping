# Review findings

Deferred work raised in review. Only tasks live here.

Findings that are really a **rule** go to `CLAUDE.md`, where they cannot be "closed" and
so cannot be forgotten. Findings that are really a **decision with tradeoffs** go to the
relevant phase entry in `docs/PLAN.md`, so they are made with the context of the work
rather than in a task list.

Every open item names the phase it should be folded into. The point is for these to be
absorbed by planned work, not to grow into a parallel backlog. Severity says whether an
item can be skipped while doing that phase: `low` can wait, `medium` should land,
`blocking` stops the phase being called done.

Closed items keep one line saying how they were resolved, so a later review does not
re-raise something already settled.

Cite code by test or symbol name, never by line number: these entries outlive the line
numbering of the files they describe.

## Open

> Items marked **Carried from** were raised for an earlier phase and not picked up.
> Skipping is a fair call, but silently is what turns a backlog into landfill: close an item
> with a reason, or say the tags are not being used and they will stop being written.


### Symlinks escape the workspace boundary — blocking

`YamlStore` documents that "every path is resolved against the workspace root and rejected
if it escapes, so a renderer bug cannot reach outside the folder the user opened". The
check is lexical only — `normalize()` collapses `..` textually and never resolves symlinks
— so a symlink inside the opened folder reaches outside it. Confirmed against the built
core, with a workspace containing `col/linked.yaml -> outside/secret.yaml` and
`col/outdir -> outside/`:

- `store.scan` walked the symlinked directory and listed files outside the workspace in the
  sidebar tree.
- `store.read` returned the contents of both the symlinked file and a file inside the
  symlinked directory.
- `store.write` created a new file outside the workspace, `outside/planted.yaml`.

Writing through a symlinked *file* did not overwrite its target, because the temp-file and
rename replaces the link rather than following it. That is luck, not defence, and it does
not apply to the directory case.

The threat model is the product's own premise. Collections are folders shared through git,
git records symlinks, and opening a cloned collection is the intended workflow — so
"a folder the user opened" is not the same as "files the user wrote". It still takes a
crafted repository and a user opening it, so this is not remotely triggerable.

Marked blocking not because the app is unusable but because the boundary is documented,
tested and absent: `StoreMethodsTest.refusesToEscapeTheWorkspace` covers only lexical
`../escape.yaml`. A boundary that is believed to exist is worse than one nobody relies on.

Fix: compare real paths, not lexical ones — resolve the target (or its nearest existing
ancestor) with `toRealPath()` against `root.toRealPath()` — and skip symlinks while
scanning. Then extend that test with a symlinked file and a symlinked directory.

Fold into: phase 7.

### Saved request files are mode 600 — low

`write` creates its temp file with `Files.createTempFile`, which is `rw-------` by design,
and the rename carries those permissions to the request file. Observed on a written file:
`-rw------- planted.yaml`, beside hand-created files at `-rw-r--r--`.

For collections meant to be committed and shared, files whose mode depends on whether Ping
or a human last wrote them are a small, lasting oddity — it shows up as spurious mode
changes in git on systems that track them. Set the permissions explicitly after the move,
or create the temp file with the process umask.

Fold into: phase 7.

### A self-referential symlink fills the sidebar — low

`ln -s .. col/loop` inside a workspace makes `children()` recurse through the link. It does
not hang — the OS symlink limit makes `Files.isDirectory` return false around forty levels
down, and the scan returns in about 150 ms — but it returns roughly 29 KB of tree that is
the same folder nested forty times. Skipping symlinks while scanning, as the escape fix
above requires anyway, removes this too.

Fold into: phase 7.

### The code editors ignore the theme — medium

Phase 9 added a light theme, but `CodeEditor.svelte` applies `oneDark` unconditionally. In
light mode the two largest surfaces in the app — the request body editor and the response
body viewer — stay dark. Measured with the light theme active:

```
app surface : rgb(243, 244, 246)
editor bg   : rgb(40, 44, 52)
```

Everything else follows `data-theme` correctly, which is what makes this stand out rather
than read as a deliberate choice.

The editor's extensions are built once in `extensions()` at mount, so switching needs
either a CodeMirror `Compartment` reconfigured when `theme.resolved` changes, or a remount
keyed on it — the components already wrap these editors in `{#key}`.

Fold into: phase 10.

### The command palette is not a real modal — low

It declares `role="dialog"` and `aria-modal="true"` but does not behave like one: focus is
not trapped, so Tab walks out of the palette into the page behind it, and focus is not
restored to the previously focused element when it closes. Inside the list, `role="option"`
wraps a focusable `<button>` — an option should not contain focusable children — and the
input carries no `aria-activedescendant`, so the highlighted row is not announced as the
selection moves.

Raised only because the same pass built `Tabs.svelte` properly, with roving tabindex,
`aria-controls` and matching `role="tabpanel"` in both consumers. The palette is the one
piece that claims a pattern without implementing it.

Fold into: phase 10.

## Closed

### Typed credentials no longer reach collection files — phase 9

On save, any literal in a secret-bearing auth field (`password`, `token`, `value`,
`clientSecret`) is moved into the shell's `safeStorage` under a request-derived name and
replaced with a `{{name}}` reference. `make smoke` types a bearer token, saves, asserts the
file holds only the reference, then sends to prove the credential is restored from the shell.

### OAuth tokens are not broadcast to the renderer — phase 9

`auth.completed` is sanitized before forwarding: `storeOAuthTokens` keeps the tokens in main
and the renderer receives only `flowId` and `error`.

### External URLs are restricted to http(s) — phase 9

`safeExternalUrl` gates both `shell.openExternal` calls — including the authorize URL built
from a collection file — and `setWindowOpenHandler`.

### The derived `configured` flag is not serialized — phase 9

`RequestSpec.Auth.isConfigured()` is `@JsonIgnore`, so saved auth blocks no longer carry a
`configured: true` line.

### Header injection is reported as bad input — phase 8

`HttpEngine` validates header names against the RFC 7230 token set and rejects CR/LF in values
with `INVALID_PARAMS`, so the JDK's raw `IllegalArgumentException` never reaches the banner.
Guarded by `HttpEngineTest.rejectsHeaderLineBreaksAsBadInput`, `rejectsInvalidHeaderNames`
and `HttpMethodsTest.reportsHeaderInjectionAsInvalidParams`.

### Multipart field names cannot break framing — phase 8

`HttpEngine.multipart` rejects a field name containing a quote or line break with
`INVALID_PARAMS`, guarded by `HttpEngineTest.rejectsMultipartNamesThatBreakFraming`.

### `make smoke` is stable and reaps its processes — phase 7

The harness spawns Electron detached, gives it a throwaway `--user-data-dir`, and on
teardown signals the whole process group and awaits `exit` with a SIGKILL fallback, so the
core and helpers are reaped instead of colliding with the next run. A leftover instance
from before the fix, holding the real profile, confirmed the leak was real.

The intermittent `timed out waiting for the cancelled state` had a second cause this
finding missed. `HttpEngine.send` registered the in-flight exchange too late — after
`sendAsync`, and before that after building the client — so a Cancel arriving during
dispatch was silently a no-op; registration is now the handler's first act, with the intent
applied whenever the future appears. The harness also clicked Cancel the instant the button
rendered, before the request had left the core, which no person can do; it now waits until
the slow endpoint has been reached. Ten consecutive runs passed.

### Multipart and the CodeMirror body are covered — phase 7

`HttpEngineTest.framesMultipartFieldsWithABoundary` builds a multipart body and asserts the
declared boundary, the CRLF framing, the closing delimiter, an exact `Content-Length`, and
that a disabled field is absent. `make smoke` types a JSON body into CodeMirror through CDP
`Input.insertText` after focusing `.cm-content`, then asserts the server received it with
`application/json`. The native suite is 53 tests.

### Run the UI smoke test in CI — phase 4

`desktop/test/smoke.mjs` skips the live HTTPS step when `PING_SMOKE_OFFLINE=1`, so the
suite no longer depends on a third party. The desktop CI job runs
`xvfb-run -a npm run smoke` headless after `make check`, covering the local, editor,
cancellation and connection-failure paths.

### The `describe()` branches are covered — phase 4

`HttpEngine.describe()` is package-private and `HttpEngineTest.rewritesTransportFailuresForPeople`
calls it directly with an `UnknownHostException`, an `HttpTimeoutException`, a
`SocketTimeoutException`, an `SSLHandshakeException` and a `ConnectException`. The timeout
branch is also asserted through a real socket in `failsWithRequestFailedWhenTheServerIsTooSlow`.

### The error banner is announced — phase 4

The banner in `App.svelte` carries `role="alert"`, so a screen reader announces a failed
request.

### Cancellation was indistinguishable from failure — phase 3

`core:request` flattened the JSON-RPC error into a string, so the renderer could not tell
a user pressing Cancel (`-32001`) from an unreachable host (`-32002`), and Electron's
"Error invoking remote method" prefix reached the UI. Fixed by carrying errors across the
bridge as a structured envelope and rethrowing a typed `CoreError` in the renderer, which
is the distinction `HttpEngine`'s cancellation flag was built to preserve. Guarded by
`make smoke`.

### Java exception class names reached users — phase 3

`HttpEngine.describe()` built messages from `getClass().getSimpleName()`, so a refused
connection read `ConnectException`. Now rewritten per failure class, and
`HttpEngineTest.surfacesConnectionFailuresAsRequestFailed` fails if a class name
reappears.

### The renderer had no type checking — phase 3

`svelte-check` was not installed and CI ran only `npm run build`, which strips types
without checking them. `make check` now runs `svelte-check`, and CI runs it before the
build. See the TypeScript pin in `CLAUDE.md`, which exists to keep this working.

### A stale response stayed on screen during a new request — phase 3

Kept deliberately, as Postman does, with `aria-busy` on the response pane so the staleness
is announced rather than hidden.
