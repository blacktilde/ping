# Ping

A lightweight, privacy-focused HTTP client that runs entirely on your local machine.

## How it fits together

Three layers, talking over newline-delimited JSON-RPC 2.0 on stdio:

- **Core** — a headless Java 25 process that does the real work: HTTP, timing, redirects,
  cancellation, `{{variable}}` interpolation, auth flows and token exchange, and the YAML
  collection store. Compiled with GraalVM `native-image` for releases, so there is no JRE to
  bundle and startup is milliseconds.
- **Shell** — Electron with a TypeScript main process: window and menus, file dialogs,
  filesystem watching, secrets in `safeStorage`, dialogs, and process lifecycle.
- **UI** — Svelte 5 with Tailwind v4, CodeMirror 6 for the two large text surfaces, sandboxed
  with no Node access. Everything it needs crosses one `core:request` choke point.

Collections are a folder per collection and a YAML file per request. Secrets never touch
them: they live in Electron `safeStorage`, and the files hold only the `{{name}}` reference.

Executed requests are kept in a shell-local history, newest first, in the sidebar's History
tab. It lives in the app's `userData`, not in the open folder, so it follows the user rather
than the workspace and is never committed with a collection. Credential-bearing auth fields
are blanked before an entry is recorded.

## Requirements

- **Java 25** to build the core. [GraalVM](https://www.graalvm.org/) 25 is additionally
  needed for native images and packaging.
- **Node.js 26** for the desktop shell.

## Quick start

```sh
make setup   # install desktop dependencies and build the core
make dev     # build the core, then start Electron with hot reload
```

On first run, with no folder open, it creates `~/Ping` with a starter collection so you have
something to send. The dev server does not rebuild Java: after editing the core, run
`make core` and restart.

## Commands

| Command     | Purpose                                              |
|-------------|------------------------------------------------------|
| `make setup`| Install desktop dependencies and build the core      |
| `make dev`  | Build the core, then start Electron with hot reload  |
| `make core` | Rebuild the core after changing Java sources         |
| `make test` | Run the core test suite on the JVM                   |
| `make build`| Production build                                     |
| `make check`| Type-check the desktop shell with svelte-check       |
| `make smoke`| Build the desktop and drive the UI over CDP          |
| `make package`| Build the native core and package for this OS      |
| `make native`| Compile the core to a native image (minutes)        |
| `make native-test`| Run the suite compiled as a native image       |
| `make agent`| Regenerate native-image reachability metadata        |

## Layout

```
ping/
├─ core/        Gradle, Java — the engine
├─ desktop/     electron-vite — main process, preload, Svelte UI
├─ contract/    JSON Schema — source of truth for the RPC boundary
└─ docs/        PLAN.md (phases and rationale), REVIEW.md (open findings)
```

## Documentation

- `docs/PLAN.md` — the stack rationale and the numbered phase plan.
- `contract/README.md` — the RPC protocol and method list.
- `CLAUDE.md` — conventions and the rules that keep the layers apart.
