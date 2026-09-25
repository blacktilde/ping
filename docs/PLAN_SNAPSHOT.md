# Ping — collection snapshots

Automatic snapshots of the workspace, so a change can be rolled back. This plan is a proposal:
the open questions at the end decide its final shape before it is folded into `PLAN.md` as a
numbered phase.

## Why

Writes are already atomic (temp file then rename), so a crash never leaves a half-written
request. What is missing is the previous version:

- `store.delete` (`YamlStore.delete`) and `store.deleteEnvironment` remove files permanently.
  They are the most destructive operations in the app and the only ones with no way back.
- `import.collection`, `store.move` and a collection rename reshape the tree in one step.
- Git covers only what the user committed. Uncommitted edits, a bad import or a deleted folder
  between commits are unprotected, and not every workspace is a repository.

## Design

### Storage: content-addressed, outside the workspace

Snapshots live under `userData/snapshots/<workspace-id>/`, never inside the workspace, so
nothing appears in the user's Git diff and no `.gitignore` entry is needed. `<workspace-id>` is a
hash of the workspace root's real path.

```
userData/snapshots/<workspace-id>/
├─ objects/<sha256>              file contents, stored once however many snapshots hold them
└─ snapshots/<timestamp>.json    { reason, createdAt, files: { "<relative path>": "<sha256>" } }
```

YAML files are small and most do not change between snapshots, so a new snapshot costs only the
files that changed. Hundreds of snapshots stay cheap.

What is captured: every file `store.scan` would see — requests, `collection.yaml`, and
everything under `environments/`. Symlinks are skipped, as `store.duplicate` skips them.

### Triggers

All automatic, and each is skipped when nothing has changed since the last snapshot (the
manifest hashes match).

1. **Before a destructive operation** — `store.delete`, `store.move`, a collection rename,
   `vars.deleteEnvironment`, `import.collection`, and a snapshot restore. The reason names the
   operation and its target (`before delete: Payments/Refund.yaml`), which is what makes each of
   them undoable.
2. **Periodically while files change** — at most one every 10 minutes, driven by the watcher in
   `workspace.ts`. This also covers external edits: a `git pull`, another editor.
3. **When a workspace is opened**, plus a manual **Snapshot now** action.

### Retention

Keep every snapshot from the last 24 hours, one per day for 30 days, then one per week.
Pruning deletes objects no remaining snapshot references. A snapshot created before a
destructive operation is kept at least 7 days whatever the schedule would drop.

### Restore

- A snapshot browser lists snapshots with their reason and time.
- Selecting one shows a diff against the current files: added, removed, changed.
- Restore a single request, a collection, or the whole workspace.
- A restore snapshots the current state first, so a restore is itself undoable.
- Restored files go through the same atomic write and the same relative-path checks as every
  other store write. A restore never writes outside the workspace root.

### Where the code goes

In the core's `store/` package, as `snapshot.create`, `snapshot.list`, `snapshot.diff` and
`snapshot.restore`. The core already owns file access and the escape checks, and the future
CLI runner can use the same methods.

The shell injects the snapshot directory, as it injects `root`; the renderer never sends or
receives a path to it. Like `import.collection`, the methods are called from dedicated IPC
handlers rather than through `core:request`, so the renderer cannot point them anywhere.

Destructive-operation snapshots are taken by the main process before it forwards the
operation, so every entry point is covered in one place.

## Quick win first: delete to trash

Independent of snapshots, `store.delete` and environment deletion should move items to the OS
trash (`shell.trashItem` in the main process) instead of deleting them permanently. It is a
small change and closes the worst case on its own. The core keeps its path validation; the main
process resolves the checked path and trashes it.

## Considerations

- **Values in environment files.** Secrets are safe — YAML holds only the variable name, and
  values live in `safeStorage`. But a value typed directly into an environment file is copied
  into `userData` with it. That value is already plain text in the workspace, so this is not a
  new exposure, but the diff view should mask environment values unless revealed.
- **No auto-commit to the user's Git repository.** Tempting, but it rewrites history the user
  owns and fails when the workspace is not a repository. Snapshots stay separate from Git.
- **RPC types.** Each new type crossing the boundary is defined in `contract/` first and needs a
  test that sends it as JSON, followed by `make agent`.
- **Size bound.** A workspace root pointed at a large directory by mistake should not copy
  gigabytes: skip files above a size cap and snapshots above a total cap, and say so.

## Steps

- [ ] **S1. Delete to trash.** Requests, folders, collections and environments go to the OS
  trash. *Gate: a deleted request is recoverable from the OS trash; path checks still refuse an
  escape.*
- [ ] **S2. Snapshot store.** `snapshot.create` and `snapshot.list` in the core, with the
  object store, manifests and retention pruning. *Gate: two snapshots with one changed file add
  one object; pruning removes only unreferenced objects.*
- [ ] **S3. Triggers.** Before destructive operations, periodic on change, on open, manual.
  *Gate: a delete followed by `snapshot.list` shows a snapshot naming that delete; an unchanged
  workspace produces no new snapshot.*
- [ ] **S4. Diff and restore.** `snapshot.diff` and `snapshot.restore` at request, collection
  and workspace level, with a pre-restore snapshot. *Gate: deleting a collection and restoring
  it yields byte-identical files; the restore itself can be undone.*
- [ ] **S5. UI.** Snapshot browser, diff view with masked environment values, restore actions.
  *Gate: the smoke test restores a deleted request through the UI.*

## Open questions

1. **Scope** — one snapshot per workspace (simpler; restore can still pick one collection), or
   separate snapshots per collection?
2. **Triggers** — all three, or only before destructive operations plus manual?
3. **Restore granularity** — file and collection level with a diff, or also field-level undo
   inside a request? The latter belongs to the editor and is a separate feature.
