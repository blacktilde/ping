# Ping

A desktop REST client. Java core compiled with GraalVM `native-image`, Electron shell,
Svelte 5 UI, talking over newline-delimited JSON-RPC 2.0 on stdio.

**Read `docs/PLAN.md` before starting work** — it holds the stack rationale, the
responsibility split between the three layers, and the numbered phase plan with its
current checkboxes. `contract/README.md` describes the RPC protocol.

## Commands

| Command     | Purpose                                              |
|-------------|------------------------------------------------------|
| `make setup`| Install desktop dependencies and build the core      |
| `make dev`  | Build the core, then start Electron with hot reload  |
| `make core` | Rebuild the core after changing Java sources         |
| `make test` | Run the core test suite                              |
| `make build`| Production build                                     |
| `make native`| Compile the core to a native image (minutes)        |
| `make native-test`| Run the suite compiled as a native image       |
| `make agent`| Regenerate native-image reachability metadata        |

The Electron dev server does not rebuild Java. After editing the core, run `make core`
and restart.

## Rules

**stdout in the core is protocol traffic only.** A stray `System.out.println` corrupts the
JSON-RPC stream and desynchronizes the client. Log to stderr; the main process forwards it
with a `[core]` prefix.

**The RPC boundary is generated from `contract/`.** Two languages share this boundary, so
change the schema first, then both sides. Do not hand-edit the types apart.

**The renderer stays sandboxed.** No Node, no filesystem, no process access. Anything it
needs crosses the `core:request` handler in the Electron main process, which is also where
argument validation and secret resolution belong.

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
