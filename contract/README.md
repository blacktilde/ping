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
| `store.create`| `{ root, collection?, type?, name }` | `{ path: string }`                       |
| `store.rename`| `{ root, path, name }`    | `{ path: string }`                                  |
| `store.move`  | `{ root, path, to }`      | `{ path: string }`                                  |
| `store.duplicate`| `{ root, path }`       | `{ path: string }`                                  |
| `store.scaffold`| `{ root, collection? }` | `{ collection: string }`                          |
| `store.delete`| `{ root, path }`          | `{}`                                                |
| `vars.catalog`| `{ root, collection }`    | `{ name, variables, environments }`                 |
| `vars.environment`| `{ root, path }`      | `EnvironmentDoc`                                    |
| `vars.saveCollection`| `{ root, collection, name?, variables? }` | `{}`                     |
| `vars.saveEnvironment`| `{ root, collection?, path?, name, variables? }` | `{ path }`       |
| `vars.resolve`| `{ root, collection, environment? }` | `{ variables: map }`                      |
| `auth.authorize`| `{ auth, variables? }`  | `{ flowId, authorizeUrl, redirectUri }`              |
| `import.curl` | `{ command: string }`     | `{ request: StoredRequest, warnings?: string[] }`   |
| `import.collection`| `{ root, content }`  | `{ collections, warnings?, secrets? }`, see `import.schema.json` |
| `run.collection`| `{ runId?, root, collection, environment?, variables? }` | `RunResult`, see `run.schema.json` |

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

### Notes (`docs`)

A request (`StoredRequest.docs`) and a collection (`collection.yaml`'s `docs`, read and written through
`vars.catalog` / `vars.saveCollection`) can carry free-form Markdown notes, kept in the file so they
travel with the collection in Git. Nothing on the wire depends on them. `vars.saveCollection` keeps the
stored notes when `docs` is omitted and clears them for an empty string. Importers use notes for what a
request cannot express (a Postman script that was not carried over) and for the source's own description
of a collection (Postman `info.description`, OpenAPI `info.description`, an Insomnia workspace's
`description`).

Notes are untrusted text (shared files, other people's imports), so the desktop renders them with raw
HTML **disabled**: `<script>` and `<img onerror=…>` are shown as text, and `javascript:` links are not
links. Links open only in the system browser, through the shell's existing external-URL handling.

### Renaming, moving and duplicating

`store.rename`, `store.move` and `store.duplicate` reshape the tree and return the new relative path.
Every path (and `to`) is relative, validated by the shell and re-checked by the core against escapes
and symlinks. Nothing is ever replaced.

- **Rename a request** sets its `name` and renames the file to match, unless the file already carries
  that name's slug (or the slug plus a numeric suffix), so a rename does not churn file names. Only
  `name` changes: the request is edited as parsed YAML, so a field this core does not know survives.
- **Rename a folder** sanitises the name for every platform and treats an existing sibling of that
  name as an error, not a silent `Name 2`. Renaming a collection also renames it in `collection.yaml`.
- **Move** takes a request or folder into an existing folder or collection. A request that would clash
  takes a unique file name; a folder that would clash is an error. Refused: a collection, the workspace
  root, an `environments` folder, and moving a folder into itself or a descendant. Moving to the current
  parent returns the same path.
- **Duplicate** copies a request (as `<name> copy`) or a whole folder or collection (recursively,
  symlinks skipped) next to the original; a copied collection is named `<name> copy` in its
  `collection.yaml`.
- `collection.yaml` and everything under `environments/` are refused by all three.
- `store.create` accepts `type: "folder"`.

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

### Files in a request body

A multipart body can carry **file parts** (a `param` with `file`) and a request can send a file as the
**whole body** (`body.type: "file"`). The core reads the file, so a large upload never crosses the
stdio pipe as base64 and is never held in memory: it is streamed with `BodyPublishers.concat`/`ofFile`,
which keeps an exact `Content-Length` (a chunked upload is refused by many servers). A file part is sent
with `filename` (default the file's name; quotes and line breaks are neutralised) and `Content-Type`
(the row's, else the file system's, else `application/octet-stream`). A request that reads a file and sets
no `timeoutMs` gets 10 minutes, not 30 seconds, because the timeout runs until response headers arrive.

**How a path is stored and who may read it.** The path is *literal*: it is never interpolated, so no
variable, environment value or captured response can redirect it.

- A file **inside the collection** is stored **relative to the collection folder** (forward slashes),
  which makes a collection with its fixtures portable in Git. The core resolves it against `filesBase`
  and refuses anything that leaves the folder, including through a symlink.
- A file **anywhere else** is stored **absolute**. The desktop shell only lets the core read an absolute
  path that a file dialog chose in this session (a *grant*) and refuses every other one before the core
  sees it, so the sandboxed renderer cannot name an arbitrary file. A restored tab, a history entry or an
  imported path outside the collection therefore asks for the file to be chosen again.
- The shell sets `filesBase` from the request's collection and overwrites whatever the renderer sent.
- **Collection runs** resolve files against the collection folder. The command line allows absolute paths
  (it is the user's own shell); `run.collection` refuses them unless `allowAbsoluteFiles` is true, and the
  desktop shell forces it false, so a shared collection cannot make the app upload an arbitrary file.

A missing, unreadable or non-regular file, an escape from the collection, and a forbidden absolute path are
all `-32602` with a message naming the field and the file, raised before anything is sent.

### Capture

`http.send` accepts a `capture` list (see `http.schema.json`) and returns a `captured` array with
one result per enabled capture, in order. A capture writes a named value out of the response into
the **runtime** variable scope, which outranks collection and environment, so login-then-call
works with no scripting: the token lands in runtime and the next request's `{{token}}` resolves
to it without a file changing.

| source     | reads                                                                   |
|------------|-------------------------------------------------------------------------|
| `jsonpath` | `target` is a path (the subset assertions use); a container is captured as its JSON text |
| `header`   | `target` is the name, case-insensitive; repeated headers are joined with `, ` |
| `status`   | the response status                                                     |

- **A miss leaves the variable absent, never empty.** The result has `found: false` and a
  message, and the next request sends `{{name}}` as written, which is visible rather than
  silently wrong. A bad name, a bad path or a body that is not JSON is a miss, not an RPC error.
- **Precedence.** The shell merges `{...variables, ...runtime, ...secrets}`, so a secret with the
  same name still wins over a captured value; the runner applies `--var` and secrets after runtime
  the same way.
- **The value is live data and stays out of sight.** `captured[].value` exists only on the hop
  from the core to the shell. The shell keeps the runtime map in memory for the session (never on
  disk, cleared when the folder changes or on request) and removes `value` before the renderer
  sees the response, so history, saved tabs, copied cURL and the UI never hold it. In a run,
  `requests[].captures` carries names and hit/miss only, and captured values are masked as `***`
  wherever they resurface, including in the request that captured them.

### run.collection and the CLI

`run.collection` runs every request in a collection folder, one after another, in sidebar
order. File paths in a request resolve against that folder (see *Files in a request body*). The handler runs on a virtual thread, so a `run.progress` notification
(`{ runId?, index, total, request }`) arrives after each request while the run is still going;
the response carries the whole `RunResult`. A request that gets no response is *errored*, one
that gets a response and fails an assertion is *failed*, and either way the run continues.

The same code backs the command line. The core binary with **no arguments** serves this
protocol on stdio, which is how the desktop shell spawns it; with arguments it is a CLI:

```
ping-core run <collection-dir> [-e NAME] [-r human|json|junit] [-o FILE] [--var NAME=VALUE]...
```

| Exit | Meaning                                                                   |
|------|---------------------------------------------------------------------------|
| 0    | every request passed                                                      |
| 1    | an assertion failed, or a request got no response                         |
| 2    | the run could not start: unknown environment, bad flag, missing folder    |

In CLI mode stdout carries only the report (or nothing, with `-o`); diagnostics go to stderr.
Secrets are read from `PING_SECRET_<NAME>` environment variables, which become the variable
`NAME`, or from `--var`, which is visible in the process list. Both outrank every other scope,
the way the shell's `safeStorage` values do, and are masked as `***` in any result they
resurface in. The OAuth2 authorization-code flow needs a browser, so a CLI run of a request
using it fails with `-32004` unless it carries an `accessToken`.

### Import

`import.curl` turns a pasted curl command into a `StoredRequest` draft and writes nothing, so
it takes no `root`. The query string becomes `query` params (percent-decoded, so the engine's
own encoding is not applied twice); `Authorization: Bearer`/`Basic` and `-u` become `auth`;
`Content-Type` and the data flags choose the body mode (`json`, `form`, `raw` or `multipart`).
Whatever a request cannot express is reported in `warnings` instead of being dropped: proxies, client certificates, HTTP version pins,
unsupported auth schemes, unknown options, and `$VAR` shell expansions, which are left as
written. A header whose name looks like a credential (`X-Api-Key`, `Cookie`, …) is kept and
flagged without echoing its value. An unusable command is `-32602`.

Credentials land in the auth fields, which the shell moves into `safeStorage` on save, so the
"secrets never touch collection files" rule holds.

`import.collection` reads a Postman collection (v2.0/v2.1), an Insomnia v4 export (JSON) or an
OpenAPI 3.0/3.1 document (JSON or YAML), detecting the format from the content, and writes a **new** collection folder under `root`: `collection.yaml`, subfolders, one file per
request, and `environments/`. A name that is already taken becomes `Name 2`, so nothing is
ever merged into or overwritten, and every name is sanitised for all platforms and written
through the store's path rules. Requests are listed alphabetically, as everywhere in the tree;
the source's order is not kept. A failure part-way removes what the call created.

- Mapped: URL (query rows keep their disabled state), headers, bodies (`json`, `raw`, `form`,
  `multipart`; GraphQL becomes a JSON envelope), `bearer`/`basic`/`apikey` auth with Postman's
  folder-to-collection inheritance, collection variables, and Insomnia's base and sub
  environments. Insomnia's `{{ _.name }}` becomes `{{name}}`.
- Files: a curl `-F name=@path` or `--data-binary @file`, a Postman file part or body, an Insomnia
  file param or file body, and an OpenAPI binary field or body become **file rows/bodies**. An
  export's path came from another machine, so each such file is also reported and has to be chosen
  again in the Body tab (a spec names no file at all, so its row starts empty).
- Reported in `warnings`: unsupported auth (`oauth2`, digest, AWS,
  Hawk, NTLM), Postman `:path` variables, Insomnia `{% %}` tags and folder environments, and
  scripts. A request's scripts and description are kept in its `docs` so the logic can be
  ported by hand.
- **OpenAPI.** One request per operation, in a folder named for its first tag. The server URL
  becomes the `baseUrl` variable (server variables replaced by their defaults), and more than one
  server makes one environment each; every URL is `{{baseUrl}}/path` with `{id}` written as
  `{{id}}`. Required query and header parameters are enabled rows and optional ones are listed
  disabled. A body comes from the document's example, else is generated from its schema (`$ref`s,
  `allOf`, `oneOf`, formats; self-referential schemas are cut where they close); JSON is preferred,
  then form encodings, then the first type listed. A request's `docs` holds its description,
  parameters and response codes. Authentication is written as **references** (`{{bearerAuth}}`,
  `{{basicAuth_username}}`) because a spec holds no credentials; the report says which secret names
  to set. Only `$ref`s inside the document are followed. Swagger 2.0, `oauth2`, `openIdConnect`,
  cookie parameters, callbacks and webhooks are reported.
- **Secrets.** Before a request is written, a literal `auth` token, password or API key value,
  and the value of any header, query parameter or form field whose name looks like a
  credential (`token`, `api_key`, `password`, `secret`, `cookie`, `authorization`), is replaced
  by `{{import-<collection>-<request>-<field>}}` and returned in `secrets`. Values that already
  contain `{{ }}` are left alone. JSON bodies are not scanned. The shell stores each secret in
  `safeStorage` and strips `secrets` from the result before the renderer sees it; the renderer
  cannot call `import.collection` through the generic channel, because that would let it pick
  the root and read the values back.
- Rejected with `-32602`, and a specific reason: not JSON or YAML, a Postman environment export,
  Postman v1, an Insomnia format other than 4 (including v5 YAML), Swagger 2.0, or an export
  with no workspace.

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
