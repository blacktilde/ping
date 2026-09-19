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


### The tab pattern is half-implemented, and now duplicated — low

The request panel has `role="tablist"`, `role="tab"` and `aria-selected`, but no
`role="tabpanel"`, no `aria-controls`/`id` pairing, and no roving tabindex or arrow-key
handling. An incomplete tab pattern is worse than none: a screen reader announces
"tab, 1 of 3" and then has no panel to move to.

This was raised for phase 5 specifically so the response viewer would not copy it. It did.
There are now three tabbed surfaces in two patterns, none complete: `role="tab"` without a
panel in both `App.svelte` and `ResponsePane.svelte`, and `aria-pressed` buttons for
pretty / raw / preview in `ResponseBody.svelte`. Fixing it once, as a shared component, is
now worth more than it was.

Fold into: phase 7. **Carried from phases 5 and 6.**

### Small corrections — low

- `{#key body.type}` around `CodeEditor` in `BodyEditor.svelte` is redundant: `json` and
  `raw` are already separate `{#if}` branches, so switching modes remounts regardless.
- `setInput(label, value)` in `desktop/test/smoke.mjs` takes the first matching element, so
  it always writes the first row. Harmless with one row, quietly wrong the first time a
  test adds two.
- `HttpRequestSpec` in `lib/http.ts` is documented as "Exactly the params `http.send`
  accepts" but omits `timeoutMs`, `redirects`, `verifyTls` and `maxBodyBytes`, whose
  defaults are applied in the core. It is a subset, and the comment should say so.

Fold into: phase 7. **Carried from phases 5 and 6.**

### The response view resets on every request — low

`ResponsePane.svelte` wraps `ResponseBody` in `{#key response}`, so a new response remounts
it and the body view returns to `pretty`. Choosing `raw`, sending again, and landing back on
`pretty` is a papercut for the send-tweak-send loop this app exists for; Postman keeps the
selection. Verified against the running app.

Fold into: phase 7. **Carried from phase 6.**

### Nothing explains why "pretty" is not pretty — low

`prettyJson` returns null when `JSON.parse` fails and the pretty view silently falls back to
the raw text. For a `text/plain` response that is right. For an `application/json` response
that is malformed it is confusing: the tab says pretty and shows unformatted text with no
reason given.

The JSON linter does not fill the gap. `linter(jsonParseLinter())` is attached in the
response path too, but in a read-only editor the document never changes after creation, so
it never reports: a non-JSON body renders with zero lint marks and zero gutter markers
(verified). Either drop the linter from the response path as dead weight, or say "not valid
JSON" next to the view buttons.

Fold into: phase 7. **Carried from phase 6.**

### The preview smoke check asserts the attribute, not the render — low

`check('renders HTML in a sandboxed frame', ...)` reads the iframe's `srcdoc` attribute
through the snapshot's fallback chain. That passes whether or not the frame rendered
anything — it would still pass if CSP blocked the frame outright. The sandboxing itself is
sound (verified separately: `sandbox=""`, an inline `<script>` in the payload does not run,
no CSP violations logged), so this is a mislabelled assertion rather than a broken feature.

Fold into: phase 7. **Carried from phase 6.**

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

### Raw Java exception text reaches the UI again — medium

A header name or value that the JDK rejects throws `IllegalArgumentException` out of
`buildRequest`, before any of the transport handling. `RpcServer` catches it as an
unexpected failure and sends `e.toString()`, so the error banner reads:

```
java.lang.IllegalArgumentException: invalid header value: "ok
X-Injected: yes"
```

This is the same defect closed in phase 3, reopened through a path its guard does not
cover: `HttpEngineTest.surfacesConnectionFailuresAsRequestFailed` only asserts on transport
failures routed through `describe()`, and this never reaches `describe()`.

It is easy to hit rather than exotic. A newline pasted into a header value does it, and so
does a variable whose value contains one — which is how a shared collection or environment
file can produce it without anyone typing a newline at all. The upside is that the JDK
does reject it: header injection through a variable is not possible, confirmed for both
names and values.

Two things to fix: validate header names and values before building the request and report
`INVALID_PARAMS` with a readable message, since this is bad input rather than an internal
fault; and widen the guard so it covers the request-building path, not only the transport
one.

Fold into: phase 8.

### Multipart field names are not escaped — low

`multipart()` builds each part by concatenating `name="` + the field name + `"`, with no
escaping or validation. A field name containing a quote and CRLF breaks out of the
`Content-Disposition` line. Observed on the wire from a field named
`a"\r\nContent-Type: text/html\r\n\r\nINJECTED`:

```
--PingBoundary…
Content-Disposition: form-data; name="a"
Content-Type: text/html

INJECTED"

v
```

The forged headers and content land inside the part. A whole extra part cannot be forged,
because the boundary is a fresh UUID the attacker cannot know, and the request is the
user's own, so the impact is shaping your own outgoing body. It becomes slightly more than
cosmetic once the name comes from `{{a variable}}` supplied by a shared collection.

RFC 7578 wants the name escaped or percent-encoded; rejecting CR and LF and escaping the
quote is enough. `HttpEngineTest.framesMultipartFieldsWithABoundary` is the natural place
for the case.

Fold into: phase 8.

### Typed credentials are written to collection files — blocking

The auth editor tells the user, on screen, beside the fields:

> Values may use `{{name}}`; secrets are stored by the shell, never in the file.

They are written to the file. Driving the real UI — open a request, Auth tab, type a
username and password, press Save — produces this on disk:

```yaml
auth:
  type: basic
  username: admin
  password: hunter2-typed-by-user
```

`authToSpec` copies `password`, `token`, `value` and `clientSecret` verbatim into the spec
and `store.write` serializes them. Collection files exist to be committed and shared, so
this puts credentials into git history under a label promising it will not.

The design around it is right, which is what makes the gap sharp: `SecretStore` encrypts
with `safeStorage`, sends only names to the renderer, and merges values into the request in
the main process. `{{secret}}` references work. Nothing routes the typed fields into it.

One part is already correct: `authToSpec` does not copy `accessToken` or `refreshToken`, so
OAuth tokens obtained by authorizing are not persisted to files by the UI path. The core
will serialize them if asked, but the UI does not ask.

Pick one and make it true: refuse to persist literal values in secret-bearing fields and
offer to store them as a named secret, writing `{{name}}` to the file; or keep persisting
them and change the label. The first matches the rule in `CLAUDE.md`.

Fold into: phase 9.

### OAuth tokens are broadcast to the renderer for no reason — medium

`OAuthTokenStore` documents that a token is handed back on the next send "without the
renderer ever holding it". The main process stores the tokens from the `auth.completed`
notification and then forwards that notification, unmodified, to the renderer — access
token, refresh token and expiry included.

The renderer does not want them. `App.svelte` reads exactly one field, `error`; nothing in
the renderer references `accessToken` or `refreshToken` at all. So the tokens cross into
the least-trusted layer — the one that renders remote response bodies — purely as a
leftover of forwarding every notification.

Strip the token fields before the send, or forward `flowId` and `error` only.

Fold into: phase 9.

### `shell.openExternal` accepts any scheme — medium

Neither call validates the URL. The authorize case matters most: `authorizeUrl` is built
from the request's `authUrl`, which comes from a collection file that may have been cloned
from someone else. Pressing Authorize hands whatever scheme it carries to the OS handler —
on Linux, straight to `xdg-open`.

`setWindowOpenHandler` has the same gap. It is harder to reach, since the renderer only
loads our own page and the preview iframe is `sandbox=""`, but the fix is identical: allow
`http:` and `https:` and drop everything else.

Fold into: phase 9.

### `configured: true` leaks into every saved auth block — low

`RequestSpec.Auth.isConfigured()` reads to Jackson as a getter, so a derived value is
serialized into the file:

```yaml
auth:
  type: basic
  configured: true
```

It is ignored on read and absent from `store.schema.json`, so it is noise rather than a
defect — but it is noise in a hand-editable, git-tracked file, and it is contract drift.
`@JsonIgnore` on the accessor removes it.

Fold into: phase 9.

## Closed

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
