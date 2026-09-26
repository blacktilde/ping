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
| `make check`| Type-check the desktop shell and run its unit tests |
| `make smoke`| Build the desktop and drive the UI over CDP          |
| `make package`| Build the native core and package for this OS      |
| `make native`| Compile the core to a native image (minutes)        |
| `make native-test`| Run the suite compiled as a native image       |
| `make agent`| Regenerate native-image reachability metadata        |
| `make ci-run`| Drive the runner CLI against a loopback server (`BIN=` for native) |

The Electron dev server does not rebuild Java. After editing the core, run `make core`
and restart.

Packaging bundles the native core as `resources/core/ping-core`, the path the main process
looks for, so it must be built on the target OS (`make package` depends on `make native`).
Signing switches on from environment alone — `CSC_LINK`/`CSC_KEY_PASSWORD` for Windows and
macOS, `APPLE_ID`/`APPLE_APP_SPECIFIC_PASSWORD`/`APPLE_TEAM_ID` for notarization — and an
unsigned CI build sets `CSC_IDENTITY_AUTO_DISCOVERY=false` instead.

**The update feed is a public repository this project controls.** electron-updater fetches
release assets over public HTTPS with no credentials, so `publish` must name a public repo —
`dbohry/ping` today. A wrong or private owner is a silent 404 at best, and a stranger
choosing what every install downloads at worst. The app checks once when packaged and never
downloads or installs without an explicit yes (`autoDownload` is false); keep it that way.

**An installer's name never contains a space.** GitHub stores an uploaded release asset with
its spaces turned into dots, and electron-updater turns the same spaces into hyphens when it
resolves a url from the feed, so a spaced name is a 404 that only appears once a user clicks
update. electron-builder's NSIS default has two, which is why `win.artifactName` is pinned;
the release workflow fails on any other artifact that grows one.

**macOS updates are verified by this project's own signature, not Apple's.** Squirrel.Mac —
what electron-updater drives on darwin — refuses to install into a build that carries no
Apple Developer ID, so macOS goes through `desktop/src/main/mac-update.ts` instead: a signed
`latest-mac.json`, a sha512 the release signed with Ed25519, `ditto` into a staging directory
beside the bundle, then a detached script that swaps it and relaunches. That signature is the
only thing standing where Apple's would be, so the checks in `mac-update-verify.ts` are not
optional and are unit tested: bytes that do not match a signature made by the release key, or
an asset URL outside this repository's releases, are never unpacked. The public key is
compiled into the app and its private half is the `MAC_UPDATE_SIGNING_KEY` secret
(`tools/mac-update-keygen.sh` makes the pair). A build whose `PUBLIC_KEY_PEM` is still the
placeholder reports an error rather than installing anything. Windows and Linux stay on
electron-updater; if a Developer ID ever appears, the macOS path can retire.

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

**A collection never chooses the network path.** The proxy and the client certificates
belong to the user: `network.json` in `userData`, injected by `withNetwork` in the main
process, which discards any `network` the renderer sends. A proxy password or certificate
passphrase is a `safeStorage` value that is *not* in `SecretStore`, whose names are offered to the
renderer and whose values are interpolable as `{{name}}`. A certificate's files are chosen by a
dialog the main process opens (`network:addCert`), so a path never comes from the renderer, and
`network:get` returns file names only. Proxy credentials go to the proxy as `Proxy-Authorization`,
never through a JDK `Authenticator`, which would turn every origin `401` into a failed request.

**Secrets never touch collection files.** They live in Electron `safeStorage`; YAML holds
only the variable name.

**A file path in a request body is chosen by the shell, never typed.** The core reads the bytes, so what
crosses the boundary is a path. A file inside the collection is stored relative to it; a file elsewhere is
stored absolute and only readable if a file dialog chose it this session (`main/files.ts`, the grants).
`http.send` refuses any other absolute path before the core sees it, the shell overwrites `filesBase`
itself, `run.*` forces `allowAbsoluteFiles: false`, and paths are literal (never interpolated). New code
that reads a file from a request must go through `FileAccess`.

**Log through `log()`, never `process.stderr.write`.** `main/log.ts` redacts each line before it is
kept, shown in the Logs view or written to `ping.log`, so a direct stderr write is a line the user cannot
see. Redaction is a safety net, not a licence: never log a request's headers, body or resolved
variables. A new store that holds a credential adds its values to `logger.maskValues` in `index.ts`.

**A cookie's value never leaves the core.** The jar is session-only memory, `cookies.list` returns no value,
and a run masks cookie values in its results. Keep it that way: the value is a session credential.

**File-writing imports go through the shell.** `import.collection` writes new folders under
the workspace root and returns the credentials it lifted out of the files. Only the
`import:collection` IPC handler may call it: it picks the file, injects the root, stores the
secrets and strips their values before the renderer sees the result. `core:request` refuses the
method for that reason. `export.collection` is the mirror image: it reads a whole collection from
the root it is given, so only the `export:collections` handler may call it, injecting the root and
writing where the user's dialog chose. An export never resolves a variable: secrets leave as
`{{name}}` references, and an absolute file path leaves as its file name.

**Notes are untrusted text.** Request and collection `docs` come from shared files and other
people's imports. `lib/markdown.ts` renders them with raw HTML disabled; keep it that way, and never
pass anything but its output to `{@html}`.

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
