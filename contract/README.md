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

**Every request that carries an `id` is answered exactly once**, with a result or an error —
a handler that fails, however unexpectedly, still replies. Silence would be invisible to the
client: handlers run on virtual threads, so one dying leaves the core serving everyone else
while that caller waits forever. The shell backs this with a 60s deadline on calls that are
not `http.send`, whose length is the user's to set.

## Methods

| Method        | Params                    | Result                                              |
|---------------|---------------------------|-----------------------------------------------------|
| `core.ping`   | `{ message?: string }`    | `{ message: string, receivedAt: number }`           |
| `core.info`   | none                      | `{ coreVersion, javaVersion, vendor, nativeImage }` |
| `http.send`   | see `http.schema.json`    | status, headers, body, timing, redirects, assertions |
| `http.cancel` | `{ requestId: string }`   | `{ cancelled: boolean }`                            |
| `store.scan`  | `{ root: string }`        | `{ collections: Node[] }`                           |
| `store.read`  | `{ root, path }`          | `StoredRequest`                                     |
| `store.write` | `{ root, path, request }` | `{ path: string }`                                  |
| `store.create`| `{ root, collection?, name }` | `{ path: string }`                              |
| `store.scaffold`| `{ root, collection? }` | `{ collection: string }`                          |
| `store.delete`| `{ root, path }`          | `{}`                                                |
| `vars.catalog`| `{ root, collection }`    | `{ name, variables, environments }`                 |
| `vars.environment`| `{ root, path }`      | `EnvironmentDoc`                                    |
| `vars.saveCollection`| `{ root, collection, name?, variables? }` | `{}`                     |
| `vars.saveEnvironment`| `{ root, collection?, path?, name, variables? }` | `{ path }`       |
| `vars.resolve`| `{ root, collection, environment? }` | `{ variables: map }`                      |
| `auth.authorize`| `{ auth, variables? }`  | `{ flowId, authorizeUrl, redirectUri }`              |

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
request. `store.create` derives a unique filename from the request name, and `store.scaffold`
creates a starter collection on first run without ever overwriting an existing one.

### vars.*

Variables live in scopes and are flattened before a request is sent. Precedence, lowest to
highest: **collection** (`collection.yaml`), **environment** (`environments/<name>.yaml`),
**runtime**. Runtime is not persisted; it is where phase 8's tokens will shadow a file value
without editing it.

`vars.resolve` returns the flattened map, and the shell passes it to `http.send` as
`variables`. Interpolation is `{{name}}` in the url, query names and values, header names
and values, and body content, content type and fields. An unknown name is left exactly as
written, so a half-configured request shows what is missing on the wire rather than silently
sending an empty value. Substitution happens in the core, so the future CLI behaves
identically.

### Auth

`http.send` accepts an `auth` object (see `http.schema.json`). Basic, bearer and API key
attach a header or query parameter. OAuth2 client credentials fetches a token from
`tokenUrl`, caches it for the life of the core process, and attaches a bearer token. OAuth2
authorization code uses the token the interactive flow obtained; without one, the send
fails with `-32004` until the user authorizes.

Auth field values are interpolated after variables are resolved, so a secret is an ordinary
variable whose value the shell read from `safeStorage`. Collection files hold only the
placeholder, and the shell merges secret values in at runtime precedence.

`auth.authorize` runs the authorization-code + PKCE flow: it opens a loopback listener and
returns the authorize URL for the shell to open in a browser. The core captures the
redirect, checks the state, exchanges the code with the verifier, caches the token under the
same key a later send looks it up by, and emits an `auth.completed` notification carrying
`{ flowId, grantKey, accessToken, refreshToken?, expiresAtMillis }` or `{ flowId, error }`.
The shell stores the tokens in `safeStorage` and restores them onto `http.send` as the
auth's `accessToken`/`refreshToken`/`expiresAtMillis`.

### Assertions

`http.send` accepts an `asserts` list (see `http.schema.json`) and returns an `assertions`
array with one result per enabled assertion, in order. They are evaluated in the core, so the
CLI runner behaves identically. There is no scripting: the predicate set is closed.

| type       | ops (default first)          | reads                                                  |
|------------|------------------------------|--------------------------------------------------------|
| `status`   | `equals`                     | `expected` is the code                                 |
| `header`   | `exists`, `equals`, `contains` | `target` is the name, case-insensitive; any value of a repeated header may match |
| `jsonpath` | `exists`, `equals`, `contains` | `target` is a path; `equals` compares a scalar's text  |
| `body`     | `contains`                   | the decoded text                                       |
| `duration` | `lt`                         | `expected` is a ceiling in ms against `timing.totalMs` |

The JSONPath subset is `$`, `.name`, `['name']` and `[n]`: enough to address one value.
A JSON `null` counts as present. `target` and `expected` are interpolated with the send's
`variables`. A misconfigured assertion (unknown type or op, bad path, non-JSON body) is a
failed result carrying a `message`, not an RPC error, so a typo in a collection file never
stops the request from being sent.

### Error codes

Beyond the JSON-RPC reserved range:

| Code     | Meaning                                                      |
|----------|--------------------------------------------------------------|
| `-32001` | Request cancelled by the caller                              |
| `-32002` | Exchange failed: DNS, connection, TLS or timeout             |
| `-32003` | Collection store failed: read, parse or write               |
| `-32004` | Authentication failed: bad configuration or token exchange  |

The engine tracks cancellation intent itself rather than inferring it from the exception
type, because the JDK surfaces an aborted exchange differently depending on how far it
had progressed.
