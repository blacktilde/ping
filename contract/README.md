# RPC contract

The desktop shell and the core are separate processes in different languages, so the
protocol between them is the one thing that must not drift. These schemas are the source
of truth; the TypeScript types and Java records are generated from them.

## Transport

Newline-delimited [JSON-RPC 2.0](https://www.jsonrpc.org/specification) over the core's
stdin/stdout. One JSON object per line.

Because Jackson and `JSON.stringify` both escape newlines inside string values, a
serialized message never contains a bare newline, so lines are a safe frame boundary.

**stdout carries protocol traffic only.** Anything else written there desynchronizes the
client. The core logs to stderr, which the Electron main process forwards with a `[core]`
prefix.

## Lifecycle

1. The main process spawns the core and holds its stdin open.
2. The core emits a `core.ready` notification listing its registered methods.
3. Requests flow; each carries an `id` that the response echoes.
4. The main process closes stdin on quit, which ends the core's read loop.

A request without an `id` is a notification: the core runs it and answers nothing.

## Methods

| Method        | Params                    | Result                                              |
|---------------|---------------------------|-----------------------------------------------------|
| `core.ping`   | `{ message?: string }`    | `{ message: string, receivedAt: number }`           |
| `core.info`   | none                      | `{ coreVersion, javaVersion, vendor, nativeImage }` |
| `http.send`   | see `http.schema.json`    | status, headers, body, timing, redirect chain       |
| `http.cancel` | `{ requestId: string }`   | `{ cancelled: boolean }`                            |
| `store.scan`  | `{ root: string }`        | `{ collections: Node[] }`                           |
| `store.read`  | `{ root, path }`          | `StoredRequest`                                     |
| `store.write` | `{ root, path, request }` | `{ path: string }`                                  |
| `store.create`| `{ root, collection?, name }` | `{ path: string }`                              |

`core.info.nativeImage` reports whether the running core is the GraalVM native image or
the JVM build, so the UI can show which one is in use.

### http.send

Handlers run on virtual threads, so `http.send` blocking for the length of an exchange
does not stall the read loop — which is what makes `http.cancel` reachable while its
request is still running. Pass a `requestId` if the request must be cancellable.

Sizes and body text are reported separately. A response over `maxBodyBytes` (10 MB by
default) is counted in full but only buffered up to the cap, with `truncated: true`, so
pointing the client at a download endpoint reports an honest size instead of exhausting
memory. Non-textual payloads report their size with `content: null`.

**Timing is partial, by design.** `dnsMs`, `ttfbMs`, `downloadMs` and `totalMs` are what
`java.net.http` can honestly report. There is no TCP-connect or TLS-handshake split: the
JDK client does not expose those, and a fabricated breakdown would be worse than an
absent one. See the phase 5 note in `docs/PLAN.md`.

### store.*

Collections live on disk as a folder per collection and a YAML file per request. The core
owns both the YAML and the file access, so the future CLI runner can read the same tree
with no Electron involved. The Electron main process owns the workspace root, the folder
dialog and the file watcher; it supplies `root` on every call and rejects paths that escape
it, and the core checks again.

`store.scan` returns the sidebar tree. A node is a `collection`, a `folder` or a `request`;
request node names come from the file's `name`, not its filename. `storedRequest` fields
mirror the `http.send` params (see `store.schema.json`), with `name` added and empty fields
omitted. Writes go through a temp file and a rename, so a crash cannot leave a half-written
request. `store.create` derives a unique filename from the request name.

### Error codes

Beyond the JSON-RPC reserved range:

| Code     | Meaning                                                      |
|----------|--------------------------------------------------------------|
| `-32001` | Request cancelled by the caller                              |
| `-32002` | Exchange failed: DNS, connection, TLS or timeout             |
| `-32003` | Collection store failed: read, parse or write               |

The engine tracks cancellation intent itself rather than inferring it from the exception
type, because the JDK surfaces an aborted exchange differently depending on how far it
had progressed.
