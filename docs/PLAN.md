# Ping — implementation plan

A desktop REST client in the spirit of Postman, Bruno and Yaak: git-friendly collections,
a polished UI, and an engine that is fast because the work never happens in JavaScript.

## Stack

| Layer     | Choice                                                                 |
|-----------|------------------------------------------------------------------------|
| Core      | Java 25, headless, compiled with GraalVM `native-image`. Gradle.        |
| Shell     | Electron, `electron-vite`, main process in TypeScript.                  |
| UI        | Svelte 5 (runes), Tailwind v4, CodeMirror 6, Bits UI or Melt UI, Lucide |
| IPC       | Newline-delimited JSON-RPC 2.0 over the core's stdin/stdout             |
| Storage   | YAML per request, a folder per collection. Secrets in Electron `safeStorage` |

### Why this shape

The core is a separate process so the HTTP engine stays testable on its own and can ship
later as a CLI/CI runner with no UI involved — it is already a standalone binary.

Java was chosen for the core deliberately. `java.net.http.HttpClient` is a strong HTTP/2
client with no dependencies, and Java 25 virtual threads make concurrent collection runs
straightforward. GraalVM removes the two costs that would otherwise disqualify it: there
is no JRE to bundle, and startup is tens of milliseconds rather than a second.

Electron was chosen over Tauri for predictability. With the real work in a native core,
the shell's only job is to render identically on every platform, and a bundled Chromium
guarantees that where Tauri's per-OS webview does not. The cost is accepted explicitly:
roughly 150 MB of Electron plus ~40 MB of core, so **Ping is not a small download**. The
speed goal survives that trade; the size goal does not.

### Alternatives considered

- **Tauri + TypeScript only** (`tauri-plugin-http`) — ~15 MB, no Rust to write, but
  reqwest reaches JS through a `fetch`-shaped hole with no DNS/TCP/TLS/TTFB breakdown.
- **Tauri + Rust core** — lightest and most capable, rejected on unfamiliarity.
- **Electron + Node core** (the Bruno stack) — simplest, one language, no IPC boundary.
  Rejected in favour of a compiled core that is reusable outside the app.
- **Compose Multiplatform Desktop** — the only modern all-Java UI path, rejected because
  it has no mature CodeMirror equivalent, and the two large text surfaces (request body,
  response viewer) are the bulk of this app's UI.
- **JVM sidecar without native-image** — same architecture, but bundling a JRE and paying
  JVM startup on every launch.

## Layout

```
ping/
├─ core/                      Gradle, Java — the engine
│  └─ src/main/java/dev/ping/
│     ├─ Main.java            stdio JSON-RPC loop
│     └─ rpc/ methods/ http/ auth/ store/ vars/
├─ desktop/                   electron-vite
│  └─ src/
│     ├─ main/                process lifecycle, secrets, dialogs
│     ├─ preload/             the renderer's entire view of the outside world
│     └─ renderer/            Svelte app
├─ contract/                  JSON Schema — source of truth for the RPC boundary
└─ docs/
```

### Responsibility split

**Core** — HTTP send, timing, redirects, cancellation, auth flows and token exchange,
YAML parse/serialize, `{{var}}` interpolation. Later: assertions and the CLI runner.

**Electron main** — window and menus, file dialogs, filesystem watching, secrets via
`safeStorage`, core process lifecycle, auto-update, opening external URLs.

**Renderer** — UI only. Sandboxed, no Node, no filesystem, no process access. Everything
crosses the `core:request` choke point in the main process.

Secrets live in `safeStorage` rather than the core because OS keychain access from a
native image means JNI work with no payoff. Resolved values travel over stdio, which is a
pipe to a child process, never a network hop.

## Phases

Phases 0–5 produce a usable tool. 6–8 complete the MVP: core request/response plus auth.

- [x] **0. Contract and skeleton.** RPC methods defined in JSON Schema, core echoes a
  ping, Electron spawns it. *Gate: a Svelte button reaches the core and renders the reply.*
- [x] **1. HTTP engine**, on the JVM for fast iteration. Method, URL, query, headers, body,
  timeout, redirect policy, cancellation, timing. *Gate: JUnit suite green against a mock server.*
- [x] **2. native-image build — early, not last.** Tracing agent over the test suite to
  generate reflection config, then a CI matrix for Linux/macOS/Windows. *Gate: the native
  binary passes the same suite.* This is the project's largest technical risk; finding an
  incompatibility here is cheap, finding it in phase 9 is not.

  **Outcome: the stack holds.** All 36 tests pass compiled as a native image, against a
  real loopback server. The binary is 33 MB and reaches `core.ready` in ~2 ms against
  ~93 ms for the JVM build — a 46x startup difference, which is the whole reason for
  choosing GraalVM over a bundled JRE. Compiling takes ~21 s today and will grow.
- [x] **3. Vertical slice.** URL bar, Send, raw response pane, against a real API.

  **Outcome: the RPC contract carried it unchanged; the Electron IPC layer did not.** No
  core method or schema moved. But the first real error path exposed that flattening a
  JSON-RPC error into a string at the `core:request` boundary loses its code, so a user
  pressing Cancel and an unreachable host arrived looking identical, with Electron's own
  "Error invoking remote method" prefix on top. Errors now cross as a structured envelope
  and are rethrown as a typed `CoreError` in the renderer — the value that `HttpEngine`'s
  cancellation `AtomicBoolean` was built to preserve. `requestId` was already in
  `http.send`, so Cancel used the existing `http.cancel`. Verified against a live HTTPS
  endpoint (200, HTTP/2) and by `make smoke`, a CDP-driven test over send, cancel and
  connection failure. Headers, cookies and the timing breakdown are left to phase 5.
- [x] **4. Request editors.** Params and headers tables, body modes (json, raw,
  form-urlencoded, multipart), CodeMirror with JSON linting.

  **Outcome: the UI was the whole cost.** The contract already carried query, headers and
  every body mode, so no core or schema change was needed. The request panel is now tabbed
  (Params / Headers / Body) over one `RequestDraft`; a single `KeyValueEditor` serves query,
  headers, form and multipart rows, and `BodyEditor` swaps in a CodeMirror 6 surface for
  JSON and raw. JSON gets `@codemirror/lang-json`'s parse linter; the theme is one-dark
  trimmed to the panel surface. **One boundary lesson:** a Svelte `$state` proxy cannot be
  structured-cloned across the context bridge, so `toRequestSpec` copies rows to plain
  objects — the first editor value that reached `http.send` failed with "an object could not
  be cloned" until it did. `make check` stays clean and `make smoke` now drives a query
  parameter, a header and a form body to the local server and asserts they arrive.
- [x] **5. Response viewer.** Pretty/raw/preview, headers, cookies, timing breakdown, size,
  search, virtualized for large payloads.

  **Outcome.** The response pane is tabbed (Body / Headers / Cookies / Timing) with count
  badges, off the `Response` record the core already returned — no core or contract change.
  Pretty and raw both render in a read-only CodeMirror, which supplies search (Ctrl-F) and
  viewport rendering for large bodies for free; preview renders HTML in a fully sandboxed
  `<iframe sandbox="">`, so no script or remote resource in a payload can run. Cookies are
  parsed from `Set-Cookie` in the renderer. `make smoke` now asserts the pretty/raw
  difference, the headers list, a parsed cookie, the timing breakdown and the HTML preview.

  **Open decision, resolved — stay on `java.net.http`.** The waterfall shows DNS, a single
  "Waiting" stage and download; it does not split TCP-connect from TLS-handshake. OkHttp's
  `EventListener` would give that, but it is a new dependency that must then be proven under
  `native-image` — the project's largest technical risk — to sharpen one bar in a secondary
  panel. The coarse split is stated in the UI itself, so it cannot be mistaken for the whole
  story. Revisit if the missing split is actually felt; the change stays behind `HttpEngine`
  and does not move the RPC contract.
- [x] **6. File store.** YAML schema in the core, sidebar tree, filesystem watching, dirty state.

  **Outcome.** Collections are a folder per collection and a YAML file per request, and the
  **core** owns both the schema and the file access (`dev.ping.store`), so the future CLI
  runner can read the same tree with no Electron. `store.scan` returns the sidebar tree,
  `store.read`/`store.write` move a `storedRequest` whose field names mirror `http.send`
  with `name` added, and `store.create` derives a unique filename. Writes go through a temp
  file and a rename. **The shell owns the root:** Electron main keeps the open folder
  (dialog, `PING_WORKSPACE`, remembered between runs), injects it on every `store.*` call,
  and rejects absolute or `..` paths before the core checks the same boundary again. A
  recursive `fs.watch`, debounced, tells the renderer to rescan. The renderer keeps the
  draft's dirty state as a fingerprint of its stored form, saves with a button or Cmd/Ctrl-S,
  and opens the first request when a folder is opened.

  **Native-image held.** `jackson-dataformat-yaml` (SnakeYAML) parsed, wrote and round-tripped
  under the image; `make agent` added the new records and `make native-test` passed all 43
  tests. The contract gained `store.schema.json` and error `-32003`.

  **Left for later, deliberately.** A file changed on disk under a dirty draft refreshes the
  tree but does not flag or reload the open request; there is no delete or rename; and
  per-request settings round-trip through files and reach `http.send` but have no controls
  yet. None of these block the MVP; the conflict case is the one worth revisiting when the
  store is next touched.
- [ ] **7. Variables and environments.** Precedence rules. Must land before auth, which depends on it.
- [ ] **8. Auth.** Basic, Bearer, API key, then OAuth2 client credentials, then auth code
  with PKCE — loopback listener in the core, browser via `shell.openExternal`, tokens in
  `safeStorage`.
- [ ] **9. Polish.** Command palette, keyboard-first navigation, themes, motion, empty states.
- [ ] **10. Packaging.** `electron-builder`, native core as an `extraResource`, per-OS CI,
  signing, updater.

## Protocol notes

RPC handlers run on virtual threads rather than inline on the read loop. A blocking
`http.send` would otherwise make the `http.cancel` for that same request unreadable.

The engine tracks cancellation intent in an `AtomicBoolean` rather than inferring it from
the exception type, because an aborted exchange surfaces as `CancellationException` or as
a wrapped I/O failure depending on how far it had progressed. Only the caller knows the
difference between a broken network and a user pressing stop.

## Reachability metadata

`native-image` must be told what is reached by reflection. Published metadata covers
Jackson itself; what this project adds is the binding onto its own records, stored in
`core/src/main/resources/META-INF/native-image/` and regenerated with `make agent`.

**The agent only sees what the tests execute.** The first agent run captured nothing for
`RequestSpec`, because the engine tests build specs with a builder and never cross
Jackson. The shipped binary would have failed on its first real request. The RPC-level
tests in `HttpMethodsTest` close that gap: they drive the core with real JSON, which is
both honest coverage and the trace the agent needs.

So **when a new type crosses the RPC boundary it needs a test that sends it as JSON**,
followed by `make agent`. `make native-test` is what catches the omission.

## Conventions

Develop against the JVM core (`make core`), which rebuilds in seconds. Build the native
image only for releases and CI gates — `native-image` takes minutes.

Two build systems live here, so `make` is the single entry point. `make dev` builds the
core and starts Electron together.
