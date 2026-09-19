# Ping

A desktop REST client. Java core compiled with GraalVM `native-image`, Electron shell,
Svelte 5 UI, talking over newline-delimited JSON-RPC 2.0 on stdio.

**Read `docs/PLAN.md` before starting work** — it holds the stack rationale, the
responsibility split between the three layers, and the numbered phase plan with its
current checkboxes. `contract/README.md` describes the RPC protocol, and `docs/REVIEW.md`
lists open review findings, each tagged with the phase it should be folded into.

## Commands

| Command     | Purpose                                              |
|-------------|------------------------------------------------------|
| `make setup`| Install desktop dependencies and build the core      |
| `make dev`  | Build the core, then start Electron with hot reload  |
| `make core` | Rebuild the core after changing Java sources         |
| `make test` | Run the core test suite                              |
| `make build`| Production build                                     |
| `make check`| Type-check the desktop shell with svelte-check       |
| `make smoke`| Build the desktop and drive the UI over CDP          |
| `make package`| Build the native core and package for this OS      |
| `make native`| Compile the core to a native image (minutes)        |
| `make native-test`| Run the suite compiled as a native image       |
| `make agent`| Regenerate native-image reachability metadata        |

The Electron dev server does not rebuild Java. After editing the core, run `make core`
and restart.

Packaging bundles the native core as `resources/core/ping-core`, the path the main process
looks for, so it must be built on the target OS (`make package` depends on `make native`).
Signing switches on from environment alone — `CSC_LINK`/`CSC_KEY_PASSWORD` for Windows and
macOS, `APPLE_ID`/`APPLE_APP_SPECIFIC_PASSWORD`/`APPLE_TEAM_ID` for notarization — and an
unsigned CI build sets `CSC_IDENTITY_AUTO_DISCOVERY=false` instead.

**There is no auto-update feed.** electron-updater fetches release assets over public HTTPS
with no credentials, so the feed has to be a public repository this project controls. Do not
add a `publish` block, an `electron-updater` dependency, or a `repository` field that a
GitHub provider can be inferred from until that repository exists; a wrong or private owner
means a silent 404 at best, and a stranger choosing what every install runs at worst.

## Rules

**stdout in the core is protocol traffic only.** A stray `System.out.println` corrupts the
JSON-RPC stream and desynchronizes the client. Log to stderr; the main process forwards it
with a `[core]` prefix.

**The RPC boundary is generated from `contract/`.** Two languages share this boundary, so
change the schema first, then both sides. Do not hand-edit the types apart.

**The renderer stays sandboxed.** No Node, no filesystem, no process access. Anything it
needs crosses the `core:request` handler in the Electron main process, which is also where
argument validation and secret resolution belong.

**The shell owns the filesystem root.** Store calls carry paths relative to the open
folder; the main process injects that root and rejects absolute or `..` paths before they
reach the core, which checks the same boundary again. A new store method takes a relative
path, never an absolute one.

**Secrets never touch collection files.** They live in Electron `safeStorage`; YAML holds
only the variable name.

**Develop against the JVM core.** `native-image` builds take minutes and are for releases
and CI gates only.

**A new type crossing the RPC boundary needs a test that sends it as JSON.** The tracing
agent that generates reachability metadata only records what the tests actually execute.
A unit test that builds an object in code proves the logic but teaches the agent nothing,
and the native binary then fails on the first real request. `make native-test` catches
this; `make agent` regenerates the metadata afterwards.

## Stack notes

- Tailwind v4: colors declared in `@theme` become utilities (`--color-panel` gives
  `bg-panel`). The older `bg-[--color-panel]` arbitrary syntax does not work.
- `electron-vite` builds the preload as CJS so the renderer can stay sandboxed; Electron
  rejects ESM preloads under a sandbox.
- Vite is pinned to 7.x because `electron-vite` 5 does not yet accept Vite 8.
- TypeScript is pinned to 6.x because `svelte-check` peers on `^5.0.0 || ^6.0.0`. Moving
  to TypeScript 7 removes the only type checking the renderer has — `npm run build` uses
  esbuild, which strips types without checking them. Revisit when `svelte-check` ships
  TypeScript 7 support.
