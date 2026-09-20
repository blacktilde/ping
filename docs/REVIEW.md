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

### Every send builds its own `HttpClient`, so no connection is ever reused — phase 1, medium

`HttpEngine.clientFor` builds a fresh client per request, because redirect policy and TLS
trust are per-request settings. The cost is that nothing survives the exchange: each send
pays a new TCP connect and TLS handshake against a host it just talked to, HTTP/2
multiplexing never engages, and each client brings its own virtual-thread executor and
selector thread that the JDK only reclaims once the client becomes unreachable.

It also distorts the panel the app is built around. A handshake the JDK does not report
separately lands inside `ttfbMs`, so the second send to the same host looks as expensive as
the first, when a reusing client would show it as much cheaper.

The fix is a small cache keyed on the settings that actually vary — timeout, redirect
policy, `verifyTls` — rather than on the request. Worth doing with the timing work, since
the numbers change when it lands.

### A response the user asked to be compressed is displayed as garbage — phase 5, medium

Nothing in the core looks at `Content-Encoding`. `java.net.http` does not send
`Accept-Encoding` itself and does not decompress, so this is invisible until a user adds
`Accept-Encoding: gzip` to the headers table — which is exactly what someone reproducing a
production call does. The gzip bytes then come back with `Content-Type: application/json`,
`HttpEngine.isTextual` says textual, and `assemble` decodes them as UTF-8, so the body pane
shows mojibake and `Size` reports the compressed length with no hint why.

curl and Postman both decode. Decoding `gzip` and `deflate` in `readBody` when the response
declares them is a contained change; the honest alternative, if it is not worth doing, is to
say so in the body pane rather than rendering the bytes as text.

### Any collection that is opened can spend any stored secret — phase 8, medium

Secrets are global to the install (`SecretStore` in `userData`), and `withSecrets` merges
all of them into the variables of every `http.send` and `auth.authorize` at the highest
precedence. Interpolation then substitutes whichever names the request happens to mention.

Collections are meant to be committed, shared and cloned — that is the point of the file
format. So a collection from someone else, opened and sent once, can name a secret it did
not create (`{{auth-token-3f2a…}}`, or any name the user chose) in a URL, header or body
pointing anywhere, and the value goes out on the wire without the renderer ever holding it
or the user seeing it. The secrets editor is write-only, so there is no screen that shows
which requests reference which secret either.

Scoping secrets to the workspace that created them, or resolving only names a request has
been granted, both close it. This is a design decision rather than a patch, which is why it
belongs with the auth work rather than in a fix.

### A credential typed into a header or a body is written to history in the clear — phase 9, medium

`historyRequest` blanks `password`, `token`, `value` and `clientSecret` on the auth block
before an entry is persisted, precisely because history is plain JSON in `userData` and
"nothing here is a secret". The same literal typed into the Headers table
(`Authorization: Bearer …`) or into a form body is recorded verbatim, so the invariant holds
only for credentials entered through the auth editor.

The same is true of the collection file — `protectAuthSecrets` also migrates auth fields
only — but a file is written when the user asks for it, whereas history is written on every
send, before any save, and is never shown as something that holds a credential. Either
extend the blanking to the fields that commonly carry one, or state in the history panel
what it keeps.

### A core that dies stays dead for the rest of the session — phase 3, medium

`CoreClient.fail` rejects every pending call and drops the child, which is right, but
nothing restarts it: after a crash (or an `exit` for any other reason) every later call
rejects with "Core is not running", so the window stays open with an app that can no longer
send a request, read a collection or save one. There is no banner saying so either — the
message arrives as whatever error the last action happened to raise.

`request` also has no timeout, so a core that is alive but not answering leaves the promise
pending forever and the UI showing "Sending…", with Cancel going to the same silent process.
A respawn with backoff plus a visible "the core stopped" state covers both.

### The core's reported version is hand-maintained — phase 10, low

`CoreMethods.VERSION` is a string literal, and `core/build.gradle.kts` carries a second
copy. The release workflow writes the tag into `desktop/package.json` with `npm version` and
never touches either, so a release cut from a tag the core does not know about reports the
previous version through `core.info`, which is what the app shows. `desktop/package-lock.json`
is already one release behind for the same reason.

Deriving `VERSION` from the Gradle build (a generated constant or a manifest attribute) makes
the drift impossible; failing that, the release workflow should set all three.

### `docs/PLAN.md` no longer describes the app — phase 11, low

`CLAUDE.md` opens by telling every contributor to read the plan first, and the plan stops at
phase 10. Request tabs (`lib/tabs.svelte.ts`), shell-local history (`main/history.ts`), the
cURL export (`lib/curl.ts`), the split panes and `store.delete` all shipped after it and
appear nowhere, so the document that is meant to carry the rationale carries none for a
sizeable part of the UI. The phase 6 entry is also stale: it says "there is no delete or
rename", and delete has since landed.

The fix is a phase entry written the way the others are — what it cost, what it taught, what
was deliberately left — not a changelog.

### Secrets outlive the requests that created them — phase 9, low

`protectAuthSecrets` stores a migrated credential under `auth-<field>-<hash(path)>`, where
`shortHash` is a 32-bit string hash. Nothing removes that entry when the request is deleted,
so the store grows names that no longer refer to anything, and the variables panel lists
them with no way to tell what they belong to. Two paths that collide in 32 bits would also
share one entry, and the second save would silently replace the first request's credential.

### An interrupted write can leave a temp file in a collection — phase 6, low

`writeValue` creates `.ping-*.yaml` beside the target so the rename is atomic on the same
filesystem. If `writeString` or `move` throws, that file stays in the user's collection
folder. It is hidden from the sidebar by the leading dot, which means the likeliest way to
notice it is in a commit. Deleting it in a `catch` costs two lines.

### A notification gets an error reply — phase 0, low

`RpcServer.invoke` calls `writeError` with the request's id whether or not there was one, so
a notification that throws is answered with an error carrying `"id": null` — which JSON-RPC
2.0 says must not be sent for a notification. The shell then treats that line as a
server-initiated message with an undefined method and quietly ignores it, so nothing breaks
today; it is the contract that is wrong, and `contract/README.md` states the rule the code
does not keep.

## Closed

### A collection name could escape the workspace — phase 6

`YamlStore.scaffold` resolved the caller's name straight against the root, so it was the one
store entry point that did not re-check the boundary the shell had already checked — an
absolute name, or one containing `..`, created directories outside the open folder. It now
creates the root first (so the check compares two real paths on first run) and resolves the
name through the same `resolve` every other method uses. Covered by
`StoreMethodsTest.refusesToScaffoldOutsideTheWorkspace`, and `withWorkspaceRoot` now also
validates `environment`, the one path-bearing field it was missing.

### The request method was uppercased in the default locale — phase 1

`RequestSpec.methodOrDefault` called `toUpperCase()` with no locale, so a lowercase
`options` became `OPTİONS` on a machine set to Turkish. Now `Locale.ROOT`, like every other
case conversion in the core, and guarded by
`HttpEngineTest.uppercasesTheMethodIndependentlyOfTheDefaultLocale`.

### The cURL export substituted fewer variables than the core — phase 9

`lib/curl.ts` matched `{{[\w.-]+}}` while `Interpolation` matches `{{[^{}]+?}}`, so a
variable whose name held anything else was substituted on the wire but left as a literal
placeholder in the copied command. The patterns now match.

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
