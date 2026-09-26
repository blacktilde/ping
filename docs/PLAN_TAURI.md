# Ping — Electron to Tauri migration plan

A staged path from the Electron shell to Tauri in which every step leaves a working app.
This is a proposal: `PLAN.md` chose Electron over Tauri for predictable rendering, and that
reasoning still holds. Start only if download size or memory has become a real complaint.

## Why staged, and what cannot be

One process cannot be half Electron and half Tauri, so the migration is not a gradual swap
of modules inside one running app. Instead:

1. **Phase A** prepares inside Electron. Each step is an ordinary release to users and is
   worth having even if the migration stops there.
2. **Phase B** builds a Tauri shell alongside, from the same renderer, gaining one area at
   a time. Electron remains the shipped product; the Tauri build is dev-only, then a preview.
3. **Phase C** cuts over once the Tauri shell reaches parity.

## What moves and what does not

| Layer | Fate |
|---|---|
| Java core | Unchanged. Bundled as a Tauri sidecar (`externalBin`), same stdio JSON-RPC. |
| Svelte renderer (~11k lines) | Unchanged apart from the shell adapter introduced in A1. |
| Electron main + preload (~3.1k lines) | Rewritten as Rust commands, or thinned by A4 first. |
| `electron-updater` + `mac-update.ts` | Replaced by `tauri-plugin-updater`, which verifies Ed25519 itself on every OS. |
| `smoke.mjs`, `screenshots.mjs` (CDP) | Moved to `tauri-driver`/WebDriver; WebKit has no CDP. |

The rules in `CLAUDE.md` (renderer sandboxed, shell owns the filesystem root, network
belongs to the user, secrets never in collection files, file grants, log redaction) carry
over unchanged. Only the language enforcing them changes. The webview's JS is untrusted in
Tauri exactly as the renderer is today.

## Phase A — prepare inside Electron

- [ ] **A1. Shell interface.** Move the type of the `window.ping` API into `shared/` and
  route the renderer's 14 direct users of `window.ping` through one adapter, `lib/shell.ts`.
  The preload becomes one implementation of that interface. No behaviour change.
- [ ] **A2. Secrets in the OS keychain.** Replace `safeStorage` for secrets, OAuth tokens
  and proxy/certificate passphrases with the OS keychain (e.g. `@napi-rs/keyring`), under
  the service and account names the Rust `keyring` crate will read. Migrate existing
  `safeStorage` values on first launch and delete the old files once written. This removes
  the riskiest part of the cutover: `safeStorage` blobs are unreadable outside Electron.
  `logger.maskValues` keeps masking every value.
- [ ] **A3. Stable data directory.** Pin `userData` to the directory a Tauri app with this
  bundle identifier uses (`app_data_dir`), moving `network.json`, history, logs and the
  remaining stores once. Afterwards both shells read the same files.
- [ ] **A4. Trust logic into the core (optional).** Move enforcement that is not inherently
  shell work into the Java core, one release per piece: the file-grant check, `network`
  injection from `network.json`, and the `import.collection` gate. The core already re-checks
  the path boundary. Each move is tested on Electron before the Rust side exists, and keeps the
  Rust shell to a relay, a keychain and dialogs (roughly 500–800 lines instead of ~3k).
  Skipping A4 means porting that logic to Rust in Phase B instead.

## Phase B — Tauri shell alongside

Lives in `desktop/src-tauri/`, loads the same renderer, and implements the A1 interface over
`invoke`/`listen`. Channels not yet ported answer "unsupported" and the UI hides the feature,
so every step below is a runnable build. CI builds it on all three OSes from B1 on.

- [ ] **B1. Core and workspace.** Sidecar spawn and supervision (the job of `core.ts`),
  `core:request`, `workspace:*`, `logs:*` with redaction. Open a folder and send requests.
  Run it on Linux first: WebKitGTK is where a rendering deal-breaker would show up.
- [ ] **B2. Credentials and network.** `secrets:*`, OAuth tokens, `network:*` including
  `network:addCert` through a shell-opened dialog. Reads the keychain entries A2 wrote, so
  secrets set in either shell are visible in both.
- [ ] **B3. Files.** `file:pick` with session grants, `response:save`, `import:collection`
  (pick, inject root, store lifted secrets, strip values before the renderer sees them).
- [ ] **B4. Remaining state.** `history:*`, `cookies:list`, `runtime:*`,
  `app:confirm-close`. Full parity with Electron.
- [ ] **B5. Updates.** `updates:*` on `tauri-plugin-updater`, feed on the same public
  repository, never downloading without an explicit yes. The release workflow signs
  `latest.json`; installer names still contain no spaces.
- [ ] **B6. Tests and preview.** Smoke and screenshot suites on `tauri-driver`; QA on
  WebView2, WKWebView and WebKitGTK, with CodeMirror on large responses as the main
  concern. Publish a separate preview artifact. Thanks to A2 and A3, a user can move between
  the two builds without losing collections, secrets or settings.

While Phase B runs, every new IPC channel is added to both shells. Keep the phase short or
freeze new shell features during it.

## Phase C — cutover

- [ ] **C1. Handover release.** A final Electron release whose updater offers the Tauri
  installer (NSIS on Windows, AppImage on Linux, the existing bundle swap on macOS). Test the
  upgrade path from every supported platform before publishing.
- [ ] **C2. Removal.** Delete `desktop/src/main`, `desktop/src/preload`, electron-builder,
  `electron-vite` and the custom macOS updater with its signing key. Update `CLAUDE.md`,
  `PLAN.md` (stack, rationale, layout) and `contract/README.md` where they name Electron.

## Effort

| Area | Size |
|---|---|
| A1 shell interface | Small (days) |
| A2–A3 keychain and data migration | Medium, risky; done while Electron can still roll back |
| A4 trust logic into the core | Medium |
| B1–B4 Rust shell | Large (weeks), much less with A4 |
| B5 updater and release workflow | Medium |
| B6 tests and cross-webview QA | Medium, ongoing |

The gain is a download of roughly 15–20 MB of shell plus the ~40 MB core instead of ~190 MB,
lower memory use, and one updater on every OS.
