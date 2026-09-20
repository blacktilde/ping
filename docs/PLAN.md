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
