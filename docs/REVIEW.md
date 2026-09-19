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

### `make smoke` is flaky and leaks processes — medium

Six consecutive runs: three passed, three failed — twice `timed out waiting for the
cancelled state`, once `renderer never appeared`. A gate that fails one run in two trains
people to ignore it.

It is not the app. Cancellation measured over stdio at the core takes 3-4 ms, identically
for a GET, a POST whose body the server ignores, and a POST whose body it drains; in the
UI, when it works, it settles in about 100 ms. Runs that start from a verified-clean
process table pass.

The cause is teardown. After every run an Electron tree and/or a `dev.ping.Main` JVM are
still alive: `app.kill()` followed immediately by `process.exit()` does not reap them, and
the next run collides with the survivors. Compounding it, the app launches against the
real user profile (`--user-data-dir` defaults to `~/.config/ping-desktop`), so a leaked
instance holds the Chromium profile lock — which is exactly what `renderer never appeared`
looks like. It also means running the test writes to the user's actual app state.

Fix: spawn detached and kill the process group, await `exit` with a SIGKILL fallback
before the harness exits, and give each run a temporary `--user-data-dir`.

Inherited from phase 3, when the harness was written, but phase 4 promoted it to the CI
gate.

Fold into: phase 5.

### Multipart and the CodeMirror body are untested — medium

`make smoke` drives query parameters, headers and a form body. It does not drive the JSON
body through CodeMirror. Multipart is untested in both the smoke test and Java —
`grep -c multipart` over the Java test sources returns zero.

Both were verified by hand and are correct today: a JSON body arrives as
`content-type: application/json` with its content intact, and a multipart body carries a
matching boundary, CRLF framing, a closing delimiter, a `Content-Length`, and excludes
disabled fields. So this is missing cover, not a defect — but a hand-rolled multipart
builder and the one component that cannot be driven by a plain value setter are precisely
what regresses without anyone noticing.

Multipart deserves a Java test, where a delimiter or CRLF slip would live. CodeMirror
cannot be reached by the smoke test's existing helpers; it needs CDP `Input.insertText`
after focusing `.cm-content`.

Fold into: phase 5.

### The tab pattern is half-implemented — low

The request panel has `role="tablist"`, `role="tab"` and `aria-selected`, but no
`role="tabpanel"`, no `aria-controls`/`id` pairing, and no roving tabindex or arrow-key
handling. An incomplete tab pattern is worse than none: a screen reader announces
"tab, 1 of 3" and then has no panel to move to.

Worth doing before phase 5 rather than after, since the response viewer adds a second
tabbed surface (pretty / raw / preview) and would otherwise copy the broken pattern.

Fold into: phase 5.

### Small corrections — low

- `{#key body.type}` around `CodeEditor` in `BodyEditor.svelte` is redundant: `json` and
  `raw` are already separate `{#if}` branches, so switching modes remounts regardless.
- `setInput(label, value)` in `desktop/test/smoke.mjs` takes the first matching element, so
  it always writes the first row. Harmless with one row, quietly wrong the first time a
  test adds two.
- `HttpRequestSpec` in `lib/http.ts` is documented as "Exactly the params `http.send`
  accepts" but omits `timeoutMs`, `redirects`, `verifyTls` and `maxBodyBytes`, whose
  defaults are applied in the core. It is a subset, and the comment should say so.

Fold into: phase 5.

## Closed

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
