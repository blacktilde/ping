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

Phases 0–12 shipped the MVP and are folded into the sections above; their outcome notes
retired with them. What follows is the next arc: the work that turns a good request sender
into something a team keeps in Git and runs in CI.

The order is dependency-first, not value-first. Assertions and capture are small on their
own, but the runner is worthless without them, so they come first. Import is independent of
everything and can be pulled forward whenever adoption matters more than depth.

**Recommended first slice: 14, 15, 17.** Import removes the wall in front of a new user;
assertions plus the runner change what the project is for.

- [ ] **13. Collection hygiene.** `store.rename`, `store.move` and `store.duplicate`; a
  folder-creating `store.create`; a filter box over the sidebar tree; and a `docs` markdown
  field on a request and on a collection. The store has grown every capability except the
  ones a collection past fifty requests needs daily. Renames and moves reuse the temp-file
  and rename discipline already in `YamlStore`, and the watcher must not drop the open tab
  when a path it holds changes underneath it — the conflict case phase 6 deliberately left.
  *Gate: `StoreMethodsTest` covers rename, move and duplicate including collisions; `make
  smoke` renames an open request and the tab survives with its dirty state intact.*

- [x] **14. Import.** Three sources, smallest first: a curl command pasted into the URL bar,
  an OpenAPI 3 document, and a Postman v2.1 or Insomnia v4 export. The parsers belong in the
  core — the CLI will want them, and a `StoredRequest` is what they all produce — behind
  `import.curl`, `import.openapi` and `import.collection`. Import writes through the existing
  store methods rather than touching disk itself, so the path rules hold with no new
  surface. An unmappable construct is reported, never silently dropped: a Postman
  pre-request script becomes a `docs` note on the request it came from.
  *Gate: a fixture corpus per format round-trips to YAML and back; each new RPC type has a
  test that sends it as JSON, then `make agent` and `make native-test`.*
  *Shipped in three slices: cURL paste (`import.curl`, the URL-bar paste handler); Postman v2.x
  and Insomnia v4 files; and OpenAPI 3.x (JSON or YAML). The file-based sources share one RPC,
  `import.collection`, which detects the format from the content, so there is a single Import
  button and no separate `import.openapi`. Also landed with it: the `docs` field on a request and
  secrets lifted into `safeStorage`.*

- [x] **15. Assertions.** A stored request gains an `asserts` list, `http.send` gains an
  `assertions` result array, and the response pane grows a pass/fail row. Declarative and
  evaluated in the core — status, header presence and value, JSONPath existence and equality,
  body contains, and a duration ceiling. **No script engine.** A JavaScript sandbox would put
  an interpreter inside a native image to buy expressiveness this tool does not need, and it
  would split behaviour between the app and the CLI the moment one of them lacked a binding.
  If the declarative set proves too narrow, the answer is more predicates, not a language.
  *Gate: unit coverage per predicate, an RPC-level test that sends `asserts` as JSON, and a
  smoke run showing one green and one red assertion.*

- [x] **16. Capture.** A `capture` list on a stored request writes named values out of a
  response — JSONPath, a header, or a status — into the **runtime** variable scope, which
  phase 7 built and left empty for exactly this. That makes login-then-call work with no
  scripting: the token lands in runtime, runtime already outranks environment and collection,
  and the next request interpolates `{{token}}` without a file changing. The shell holds the
  runtime map for the session and the runner holds it for the length of a run.
  *Gate: a two-request smoke — one send captures a value the next send puts on the wire —
  plus a core test that a capture miss leaves the variable absent rather than empty.*

- [x] **17. Runner and CLI.** The core is already a standalone binary; this phase gives it a
  second mode. `run.collection` runs a folder with a chosen environment, on virtual threads,
  emitting per-request progress as notifications, and `ping run <collection> -e prod` does the
  same from a terminal with no Electron. Reporters: human-readable, JSON, and JUnit XML for CI.
  Exit code is non-zero when an assertion fails. Argv handling must not disturb the default:
  **no arguments means the stdio RPC loop**, because that is how the shell spawns it.
  *Gate: CI runs a sample collection against a loopback server, once on the JVM and once as
  the native image, and a deliberately failing assertion fails the job.*

- [ ] **18. Bodies that come from disk.** Multipart file parts and a binary file body. Today
  `multipart()` builds a `StringBuilder` and sends it with `ofString`, so a file part would be
  corrupted even if there were a way to name one; the publisher becomes `ofByteArrays` over
  streamed parts so a large upload is never held in memory twice.
  **Open decision — how a file path crosses the boundary.** Reading the file in the shell and
  passing bytes through JSON-RPC is the cheapest change and the wrong one: a 200 MB upload
  would cross the context bridge as base64. The core should read the path. That breaks the
  "store calls carry relative paths" rule unless the path is a *grant*: the shell records the
  paths a file dialog produced this session and rejects anything else before the core sees it.
  Decide this in the phase, and if the grant model holds, it becomes a rule in `CLAUDE.md`.
  *Gate: a multipart upload with a real file arrives byte-identical at a loopback server, and
  a path the user never chose is refused by the shell.*

- [ ] **19. Network reality.** Proxies, client certificates, and an honest connect split.
  A proxy (system or manual, with `Proxy-Authorization`) is what makes the tool usable inside
  a corporate network, and mTLS is what makes it usable against one — a PKCS#12 or PEM client
  cert whose passphrase lives in `safeStorage` like any other secret and reaches the core as a
  resolved value. Add a per-request HTTP version pin for servers that misbehave on h2.
  **The timing split, honestly.** Phase 5 refused a fabricated TCP/TLS breakdown and that
  stands: `java.net.http` will not report it. An opt-in probe can measure it truthfully on a
  *separate* socket — time `Socket.connect`, then `startHandshake` — and the UI labels it as a
  probe rather than as part of the measured exchange. A second connection is a different
  connection; saying so is the whole point.
  *Gate: a loopback proxy sees the request; a loopback server requiring a client cert accepts
  it and rejects the same request without one.*

- [ ] **20. Cookie jar.** Sends are stateless today — `Set-Cookie` is parsed for display and
  then forgotten, so any session-based API takes a hand-copied header. A jar scoped per
  collection and environment, viewable and clearable in the UI, with a per-request opt-out.
  The jar lives in the core so the runner behaves identically across a multi-step run, and
  persistence is a decision for the phase: a session-lifetime jar is safe, a persisted one is
  a credential on disk and would have to live where secrets live, not in YAML.
  *Gate: a login response sets a cookie and the following request carries it; a second
  environment does not see it.*

- [ ] **21. Streaming responses.** `text/event-stream` currently buffers until the timeout
  fires, which makes an SSE endpoint look broken. Stream the body as `http.chunk`
  notifications keyed by `requestId`, render incrementally, and keep the existing cancel path
  working mid-stream. The shell's 60s `RESPONSE_TIMEOUT_MS` must exempt a streaming send the
  same way it already exempts `http.send`.
  **WebSocket is assessed here, not assumed.** It is a different lifecycle — bidirectional,
  long-lived, with its own send box — and it earns its own phase or a "no" rather than being
  smuggled in behind SSE.
  *Gate: a loopback SSE server's events appear as they arrive, and cancelling mid-stream ends
  the exchange rather than leaking the connection.*

- [ ] **22. Response tooling and body transport.** A JSONPath filter box, a collapsible JSON
  tree, and a diff between two runs of the same request — the last is rare in this class of
  tool and cheap once responses are addressable. Behind it sits a transport problem: a 10 MB
  body crosses stdio as JSON, then the context bridge, then becomes a CodeMirror document.
  Bodies over roughly a megabyte should spill to a temp file and cross as a handle the
  renderer reads on demand, which also makes "save response" a rename instead of a copy.
  *Gate: a 50 MB response renders without stalling the renderer, and its saved file is
  byte-identical to what the server sent.*

- [ ] **23. Auth expansion.** AWS SigV4 first — it is the one missing scheme with a large
  population behind it — then HTTP Digest, then the OAuth2 device code flow. Each is a
  variant inside `Authenticator`, and each needs a test that sends its config as JSON before
  the metadata is regenerated.
  *Gate: a SigV4 signature matches a known-good vector from the AWS test suite.*

- [ ] **24. GraphQL.** A body mode with separate query and variables panes, sent as an
  ordinary JSON body, plus schema-driven completion in CodeMirror from an introspection
  query. Almost entirely a renderer phase: the wire format is already supported.
  *Gate: a query with variables reaches a loopback server as the expected JSON envelope.*

- [ ] **25. History and workspace.** History is capped at 100, unsearchable and unexportable,
  which makes it a log rather than a tool; add search, pinning, export, and a larger cap with
  an index. Alongside it, recent workspace roots and a switcher, so more than one folder is
  reachable without a dialog.
  *Gate: search finds an entry beyond the old cap, and a pinned entry survives a clear.*

- [ ] **26. Code generation.** `curl.ts` is already pure string work over a draft; give it
  siblings for `fetch`, Python `requests`, HTTPie and Go, behind one interface and one menu.
  Generated output keeps unresolved `{{placeholders}}` exactly as the curl export does, so a
  snippet pasted into a chat never carries a secret.
  *Gate: each generator round-trips through its own language's parser in a unit test where
  one exists, and the secret-placeholder rule is asserted per generator.*

### Not planned

Recorded so they are not re-raised. **A scripting sandbox** — see phase 15; predicates and
captures cover the cases that matter without putting an interpreter in a native image.
**gRPC and Protobuf** — a different stack (schema registry, code generation, streaming
semantics) wearing the same button; it belongs in a different tool or a much later arc.
**Cloud sync, accounts and telemetry** — the README's promise is that nothing leaves the
machine, and Git already syncs a folder of YAML better than a service would.

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
