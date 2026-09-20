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

### `rocket-daylight` ships a placeholder photograph — blocking, phase 9

`desktop/src/renderer/src/assets/rocket-daylight.webp` is a synthetic gradient standing in
for the intended artwork, which could not be transferred into the session that built the
theme. Everything else about `rocket-daylight` is finished: tokens, the light scrim, the
cycle entry, and its exclusion from `DARK` in `lib/theme.svelte.ts`.

Overwrite that one file with the real image, at roughly 16:9 so the `cover` crop behaves,
and the theme is done — no CSS or TypeScript changes follow from it. Do not release while
this stands: the placeholder is not the intended artwork and looks like a plain gradient.

The theme must stay a *light* theme when the real image lands. It carries a photograph but
the surface underneath it is white, so it belongs outside `DARK`, next to `light`.

## Closed

### The update feed points at the project's own public repository — phase 10

`anomalyco` was confirmed third party, so the `publish` block and
`desktop/src/main/updater.ts` were removed while the only remote was private. The repository
is now public (`dbohry/ping`), so `publish` names it, electron-builder emits the `latest*.yml`
feed plus `app-update.yml`, and a manual release run builds the installers per OS and creates
the GitHub Release for the tag it is given. `startUpdater` checks once in a packaged build but
downloads and installs only after a dialog answer — `autoDownload` is false, per this finding.
`make smoke` and the packaged-app check still pass.

### The hardened runtime keeps only what V8 needs — phase 10

`com.apple.security.cs.disable-library-validation` is gone from
`build/entitlements.mac.plist`; only `allow-jit` and `allow-unsigned-executable-memory`
remain, which V8 genuinely requires. Still unexercised until someone signs a build with a
real certificate — the check then is that the bundled core still launches as a child.

### The packaged app describes itself — phase 10

The header reads "A desktop REST client" instead of the stale phase label. Confirmed in a
rebuilt AppImage, which also reported `mode native-image` and completed a live HTTPS request.
`desktopName` and `syncDesktopName` are set, so electron-builder no longer warns about
window association.


### Symlinks cannot escape the workspace — phase 9

`YamlStore.resolve` also compares the real path of the deepest existing ancestor against
`root.toRealPath()`, so a symlink pointing outside is rejected for read and write, and
`scan`/`children`/`environmentNames` skip symlinks entirely — which also stops a
self-referential link filling the tree. Covered by
`StoreMethodsTest.refusesSymlinksThatLeaveTheWorkspace`.

### Saved request files are shareable — phase 9

`writeValue` sets `rw-r--r--` after the rename where the filesystem has POSIX modes, so a
file Ping wrote matches one a person wrote. Covered by
`StoreMethodsTest.writtenFilesAreReadableByOthers`.

### The editors follow the theme — phase 9

`CodeEditor` swaps its CodeMirror theme through a `Compartment` when `theme.resolved`
changes: oneDark on dark, a light layer over the fallback highlight style on light. `make
smoke` reads the editor text colour before and after a theme switch and asserts it changes.

### The command palette is a real combobox — phase 9

The input is `role="combobox"` with `aria-controls` and `aria-activedescendant` onto the
highlighted option; options are non-focusable `role="option"` elements; Tab is trapped while
open; and focus returns to whatever opened it when it closes.

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
