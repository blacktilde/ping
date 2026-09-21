/**
 * UI smoke test.
 *
 * Drives the built app over the Chrome DevTools Protocol and reads the DOM back, because
 * synthetic keyboard input does not reach Electron on every setup. It covers the paths
 * that are easy to get wrong: a normal exchange, the request editors reaching the wire,
 * a cancellation that must not look like a failure, and an unreachable host that must not
 * leak Java class names or Electron's internal IPC prefix.
 *
 * Run `npm run build` first, then `npm run smoke`. Set `PING_SMOKE_OFFLINE=1` to skip the
 * live HTTPS check, which is what CI does so the suite does not depend on a third party.
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import https from 'node:https'
import { tmpdir } from 'node:os'
import { delimiter, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { buildSync } from 'esbuild'
import electron from 'electron'

const PORT = 8791
const DEBUG_PORT = 9225
const root = new URL('..', import.meta.url)
const base = `http://127.0.0.1:${PORT}`

// Set when the slow endpoint is reached, so the cancel step can click only once the request
// has actually left the core. Clicking the instant the Cancel button appears races dispatch.
let slowStarted = false
let lastTokenForm = ''

// Echoes what reached the server so the test can prove the editors are wired to the wire.
let lastUpload = null
// A feed: one event at once, the second only after /events/release is hit, then keep-alives until the
// client goes away. `feedClosed` proves Stop really released the connection.
let feedClosed = false
let feedRelease = null
const server = http.createServer(async (req, res) => {
  if (req.url.startsWith('/events/release')) {
    feedRelease?.()
    res.writeHead(204)
    res.end()
    return
  }
  if (req.url.startsWith('/events')) {
    feedClosed = false
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
    res.write('id: 1\nevent: tick\ndata: {"n":1}\n\n')
    let timer
    res.on('close', () => {
      feedClosed = true
      clearInterval(timer)
    })
    feedRelease = () => {
      res.write('id: 2\nevent: tick\ndata: {"n":2}\n\n')
      timer = setInterval(() => res.write(': keepalive\n\n'), 100)
    }
    return
  }
  if (req.url.startsWith('/slow')) {
    slowStarted = true
    setTimeout(() => {
      res.writeHead(200, { 'Content-Type': 'text/plain' })
      res.end('late')
    }, 30_000)
    return
  }

  if (req.url.startsWith('/oauth/token')) {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    lastTokenForm = Buffer.concat(chunks).toString('utf8')
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ access_token: 'smoke-token', token_type: 'Bearer', expires_in: 3600 }))
    return
  }

  if (req.url.startsWith('/html')) {
    res.writeHead(200, {
      'Content-Type': 'text/html',
      'Set-Cookie': 'smoke=yes; Path=/; HttpOnly'
    })
    res.end('<!doctype html><h1>preview me</h1>')
    return
  }

  if (req.url.startsWith('/large')) {
    // Long enough to overflow any viewport: the response pane must clip it, not grow the
    // split container until the resize handle is scrolled off-screen.
    const items = Array.from({ length: 500 }, (_, i) => ({ id: i, name: `item-${i}` }))
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ items }))
    return
  }

  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  // The raw bytes of the last upload, for the file-body checks; the echo below is text.
  if (req.url.startsWith('/upload')) lastUpload = { headers: req.headers, body: Buffer.concat(chunks) }

  res.writeHead(200, {
    'Content-Type': 'application/json',
    'Set-Cookie': 'smoke=yes; Path=/; HttpOnly'
  })
  res.end(
    JSON.stringify({
      from: 'local test server',
      method: req.method,
      url: req.url,
      headers: req.headers,
      body: Buffer.concat(chunks).toString('utf8')
    })
  )
})
await new Promise((resolve) => server.listen(PORT, '127.0.0.1', resolve))

// A server that insists on a client certificate signed by the fixture CA, and says whose it was.
// The files are throwaway test material (see fixtures/tls/README.md).
const tls = (name) => fileURLToPath(new URL(`./fixtures/tls/${name}`, import.meta.url))
const MTLS_PORT = 8793
const mtls = https.createServer(
  {
    key: readFileSync(tls('server.key')),
    cert: readFileSync(tls('server.pem')),
    ca: readFileSync(tls('ca.pem')),
    requestCert: true,
    rejectUnauthorized: true
  },
  (req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end(req.socket.getPeerCertificate()?.subject?.CN ?? 'none')
  }
)
await new Promise((resolve) => mtls.listen(MTLS_PORT, '127.0.0.1', resolve))

// A stand-in HTTP proxy: it answers for any host, and records what it was asked and the
// credentials it was shown, so the test can prove a request went through it (or did not).
const PROXY_PORT = 8792
const proxied = []
const proxy = http.createServer((req, res) => {
  proxied.push({ target: req.url, auth: req.headers['proxy-authorization'] })
  res.writeHead(200, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ from: 'smoke proxy', target: req.url }))
})
await new Promise((resolve) => proxy.listen(PROXY_PORT, '127.0.0.1', resolve))

// A throwaway collection so the test can prove load, dirty, save and watching without a
// folder dialog. The shell takes its workspace from PING_WORKSPACE, which is also how CI
// runs headless.
const workspaceDir = mkdtempSync(join(tmpdir(), 'ping-smoke-'))
mkdirSync(join(workspaceDir, 'demo', 'environments'), { recursive: true })
const savedRequest = join(workspaceDir, 'demo', 'get.yaml')
writeFileSync(
  savedRequest,
  [
    'name: Smoke get',
    'method: GET',
    "url: '{{base}}/data'",
    'query:',
    '  - name: q',
    "    value: '{{token}}'",
    'headers:',
    '  - name: X-Only',
    "    value: '{{only}}'",
    ''
  ].join('\n')
)
writeFileSync(
  join(workspaceDir, 'demo', 'collection.yaml'),
  [
    'name: Demo',
    'variables:',
    '  - name: base',
    '    value: http://127.0.0.1:1',
    '  - name: token',
    '    value: collection-token',
    '  - name: only',
    '    value: collection-only',
    ''
  ].join('\n')
)
writeFileSync(
  join(workspaceDir, 'demo', 'environments', 'dev.yaml'),
  [
    'name: Dev',
    'variables:',
    '  - name: base',
    `    value: ${base}`,
    '  - name: token',
    '    value: environment-token',
    ''
  ].join('\n')
)

// A second collection, for the run panel. Its own variable points nowhere, so a run with no
// environment errors on every request; the Dev environment points at the loopback server, where
// one assertion holds and one deliberately does not. The names avoid the words the sidebar
// filter step matches on.
mkdirSync(join(workspaceDir, 'run-demo', 'environments'), { recursive: true })
writeFileSync(
  join(workspaceDir, 'run-demo', 'collection.yaml'),
  ['name: run demo', 'variables:', '  - name: host', '    value: http://127.0.0.1:1', ''].join('\n')
)
writeFileSync(
  join(workspaceDir, 'run-demo', 'environments', 'dev.yaml'),
  ['name: Dev', 'variables:', '  - name: host', `    value: ${base}`, ''].join('\n')
)
writeFileSync(
  join(workspaceDir, 'run-demo', 'one-green.yaml'),
  [
    'name: One green',
    'method: GET',
    "url: '{{host}}/data'",
    'asserts:',
    '  - type: status',
    '    op: equals',
    "    expected: '200'",
    ''
  ].join('\n')
)
writeFileSync(
  join(workspaceDir, 'run-demo', 'two-red.yaml'),
  [
    'name: Two red',
    'method: GET',
    "url: '{{host}}/data'",
    'asserts:',
    '  - type: status',
    '    op: equals',
    "    expected: '500'",
    ''
  ].join('\n')
)

// A Postman export the import flow reads. The shell normally asks a file dialog; the smoke
// test cannot drive a native dialog, so PING_IMPORT_FILE stands in for it.
const importFile = join(workspaceDir, '..', `ping-smoke-import-${process.pid}.json`)
writeFileSync(
  importFile,
  JSON.stringify({
    info: {
      name: 'Imported demo',
      schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'
    },
    variable: [{ key: 'host', value: base }],
    item: [
      {
        name: 'Whoami',
        event: [{ listen: 'test', script: { exec: ["pm.test('ok', () => {});"] } }],
        request: {
          method: 'GET',
          url: { raw: '{{host}}/whoami?from=postman' },
          auth: { type: 'bearer', bearer: [{ key: 'token', value: 'smoke-import-token' }] },
          description: 'Who am I.'
        }
      },
      {
        name: 'Upload',
        request: {
          method: 'PUT',
          url: '{{host}}/upload',
          body: { mode: 'file', file: { src: '/tmp/payload.bin' } }
        }
      }
    ]
  })
)

// Files for the upload section. The bytes are ones a text-oriented implementation would mangle.
// One lives outside the workspace (stored absolute, readable only once a dialog chose it) and
// one inside the collection (stored relative to it).
const uploadBytes = (seed) =>
  Buffer.concat([Buffer.from([0, 1, 2, 255, 254, 13, 10, 13, 10, seed]), Buffer.from('--PingBoundaryDecoy\r\n')])
const outsideUpload = join(tmpdir(), `ping-smoke-upload-${process.pid}.bin`)
writeFileSync(outsideUpload, uploadBytes(7))
mkdirSync(join(workspaceDir, 'demo', 'fixtures'), { recursive: true })
const insideUpload = join(workspaceDir, 'demo', 'fixtures', 'logo.bin')
writeFileSync(insideUpload, uploadBytes(9))

const userDataDir = mkdtempSync(join(tmpdir(), 'ping-smoke-userdata-'))

const app = spawn(
  electron,
  [
    '.',
    `--remote-debugging-port=${DEBUG_PORT}`,
    '--disable-gpu',
    '--no-sandbox',
    // Never touch the real profile: a leaked instance would hold the Chromium profile lock
    // and the next run would fail with "renderer never appeared".
    `--user-data-dir=${userDataDir}`
  ],
  {
    cwd: root,
    stdio: ['ignore', 'pipe', 'pipe'],
    // Own process group, so teardown can signal Electron, its helpers and the core at once.
    detached: true,
    // The fake updater drives the same state machine without a network, so the in-app flow
    // is covered even though a source build is not `isPackaged`.
    env: {
      ...process.env,
      PING_WORKSPACE: workspaceDir,
      PING_FAKE_UPDATE: '1',
      PING_IMPORT_FILE: importFile,
      // Consumed in order: the first pick is the outside file, the second the one in the collection.
      PING_UPLOAD_FILE: [outsideUpload, insideUpload].join(delimiter),
      // Stand in for the certificate dialogs, consumed in order: a PKCS#12 bundle, then a PEM
      // certificate and its key.
      PING_CERT_FILE: [tls('client.p12'), tls('client.pem'), tls('client.pk8.pem')].join(delimiter)
    }
  }
)
app.stderr.on('data', (chunk) => {
  const text = String(chunk)
  if (/\[core\]|\[renderer\]/.test(text)) process.stderr.write(text)
})

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

let failures = 0
function check(label, condition, detail = '') {
  if (!condition) failures++
  console.log(`[${condition ? 'PASS' : 'FAIL'}] ${label}${detail ? ` — ${detail}` : ''}`)
}

let socket
try {
  const page = await findPage()
  if (!page) {
    // Throw rather than exit: process.exit skips the finally that reaps the app.
    throw new Error('renderer never appeared')
  }

  socket = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((resolve) => (socket.onopen = resolve))

  let nextId = 1
  const pending = new Map()
  socket.onmessage = (event) => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message)
      pending.delete(message.id)
    }
  }

  function cdp(method, params = {}) {
    const id = nextId++
    socket.send(JSON.stringify({ id, method, params }))
    return new Promise((resolve) => pending.set(id, resolve))
  }

  async function evaluate(expression) {
    const response = await cdp('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true
    })
    if (response.result?.exceptionDetails) {
      throw new Error(JSON.stringify(response.result.exceptionDetails))
    }
    return response.result?.result?.value
  }

  await cdp('Runtime.enable')

  const setInput = (label, value) => `(() => {
    const inputs = document.querySelectorAll('input[aria-label="${label}"]');
    const input = inputs[inputs.length - 1];
    if (!input) return null;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, ${JSON.stringify(value)});
    input.dispatchEvent(new Event('input', { bubbles: true }));
    return input.value;
  })()`

  const setSelect = (label, value) => `(() => {
    const select = document.querySelector('select[aria-label="${label}"]');
    if (!select) return null;
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
    setter.call(select, ${JSON.stringify(value)});
    select.dispatchEvent(new Event('change', { bubbles: true }));
    return select.value;
  })()`

  const clickText = (text) => `(() => {
    const button = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(text)});
    if (!button) return false;
    button.click();
    return true;
  })()`

  const clickTab = (label) => `(() => {
    const tab = [...document.querySelectorAll('[role=tab]')].find(t => t.textContent.trim().startsWith(${JSON.stringify(label)}));
    if (!tab) return false;
    tab.click();
    return true;
  })()`

  const setUrl = (value) => setInput('Request URL', value)
  const setMethod = (value) => setSelect('HTTP method', value)

  const snapshot = `(() => {
    const pane = document.querySelector('[data-role="response"]');
    const error = document.querySelector('[data-role="error"]');
    const cancelled = document.querySelector('[data-role="cancelled"]');
    const submit = document.querySelector('button[type=submit]');
    const body = pane?.querySelector('.cm-content')?.textContent
      ?? pane?.querySelector('iframe')?.getAttribute('srcdoc')
      ?? pane?.querySelector('pre')?.textContent
      ?? '';
    return {
      status: pane?.querySelector('header span')?.textContent.trim() ?? null,
      body: body.slice(0, 8000),
      paneText: (pane?.textContent ?? '').slice(0, 8000),
      error: error ? error.textContent.trim() : null,
      cancelled: cancelled ? cancelled.textContent.trim() : null,
      sendLabel: submit ? submit.textContent.trim() : null,
      cancelVisible: [...document.querySelectorAll('button')].some(b => b.textContent.trim() === 'Cancel'),
    };
  })()`

  const clickResponseTab = (label) => `(() => {
    const root = document.querySelector('[data-role="response"]');
    const tab = root && [...root.querySelectorAll('[role=tab]')].find(t => t.textContent.trim().startsWith(${JSON.stringify(label)}));
    if (!tab) return false;
    tab.click();
    return true;
  })()`

  const clickResponseView = (label) => `(() => {
    const root = document.querySelector('[data-role="response"]');
    const button = root && [...root.querySelectorAll('button')].find(b => b.textContent.trim().toLowerCase() === ${JSON.stringify(label)});
    if (!button) return false;
    button.click();
    return true;
  })()`

  const snap = () => evaluate(snapshot)

  /**
   * Clicks Send once the button will take it. Send is disabled while an exchange is in
   * flight and a click on a disabled button is dropped without a trace, so clicking a
   * moment too early leaves the previous response on screen until the wait for the next one
   * gives up — a failure that looks like a broken request rather than a missed click.
   */
  async function clickSend() {
    await waitFor(
      async () => await evaluate(`document.querySelector('button[type=submit]')?.disabled === false`),
      10_000,
      'the Send button to accept a click'
    )
    await evaluate(`document.querySelector('button[type=submit]').click()`)
  }

  /**
   * A timeout throws, which abandons every check after it, so `describe` puts what was on
   * screen into the message: a bare "timed out" says only what was missing.
   */
  async function waitFor(predicate, timeoutMs, label, describe) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (await predicate()) return
      await wait(200)
    }
    const detail = describe ? await describe().catch((cause) => `could not be read: ${cause}`) : ''
    throw new Error(`timed out waiting for ${label}${detail ? ` — ${detail}` : ''}`)
  }

  /** What the request and response panes hold, for a timeout that has to explain itself. */
  async function paneState() {
    const state = await snap()
    const url = await evaluate(`document.querySelector('input[aria-label="Request URL"]')?.value ?? null`)
    return `url ${url ?? 'none'}, send ${state.sendLabel ?? 'none'}, status ${state.status ?? 'none'}, error ${state.error ?? 'none'}, body ${JSON.stringify(state.body.slice(0, 200))}`
  }

  /** Waits for the response pane to show text the request itself put there. */
  const waitForEcho = (text, timeoutMs = 5000) =>
    waitFor(
      async () => (await snap()).body.includes(text),
      timeoutMs,
      `the echoed request to mention ${text}`,
      paneState
    )

  /**
   * Unsaved-changes prompts are the styled in-app dialog, so the script answers it in the
   * DOM. Returns false when no prompt turned up, so conditional confirmations stay one call.
   */
  async function acceptPrompt(timeoutMs = 2000) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (await evaluate(`!!document.querySelector('[data-role="confirm-accept"]')`)) {
        await evaluate(`document.querySelector('[data-role="confirm-accept"]').click()`)
        return true
      }
      await wait(100)
    }
    return false
  }

  function echo(bodyText) {
    try {
      return JSON.parse(bodyText)
    } catch {
      return null
    }
  }

  console.log('--- 0. workspace: load, dirty, save, watch')
  const sidebarText = async () =>
    await evaluate(`document.querySelector('[data-role="sidebar"]')?.textContent ?? ''`)
  await waitFor(async () => (await sidebarText()).includes('Smoke get'), 8000, 'the tree to load')
  check('shows the collection tree', (await sidebarText()).includes('demo'))

  await waitFor(
    async () =>
      (await evaluate(`document.querySelector('input[aria-label="Request URL"]')?.value`)) ===
      '{{base}}/data',
    5000,
    'the first request to open'
  )
  check(
    'loads the request from disk',
    (await evaluate(`document.querySelector('input[aria-label="Request URL"]')?.value`)) ===
      '{{base}}/data'
  )
  check('starts clean', !(await evaluate(`!!document.querySelector('[data-role="dirty"]')`)))

  await evaluate(setInput('Request URL', `${base}/changed`))
  await waitFor(
    async () => await evaluate(`!!document.querySelector('[data-role="dirty"]')`),
    2000,
    'the dirty marker'
  )
  check('marks unsaved edits', true)

  await evaluate(`document.querySelector('[data-role="save"]').click()`)
  await waitFor(
    async () => !(await evaluate(`!!document.querySelector('[data-role="dirty"]')`)),
    3000,
    'the save to finish'
  )
  check('writes the edit to disk', readFileSync(savedRequest, 'utf8').includes('/changed'))

  writeFileSync(
    join(workspaceDir, 'demo', 'added.yaml'),
    'name: Added remotely\nmethod: GET\nurl: https://example.com\n'
  )
  await waitFor(
    async () => (await sidebarText()).includes('Added remotely'),
    8000,
    'the watcher to refresh the tree'
  )
  check('watcher refreshes the tree', true)

  console.log('--- 1. exchange with the local server')
  await evaluate(setUrl(`${base}/data`))
  await evaluate(setMethod('GET'))
  await clickSend()
  await waitFor(async () => (await snap()).status === '200', 5000, 'a 200')
  const local = await snap()
  check('renders the status', local.status === '200', local.status ?? 'none')
  check('renders the body', local.body.includes('local test server'), local.body.slice(0, 80))
  check('shows no error', local.error === null)

  console.log('--- 2. query parameters and headers reach the server')
  await evaluate(clickTab('Params'))
  await evaluate(clickText('+ Add parameter'))
  await evaluate(setInput('Query parameter', 'smoke'))
  await evaluate(setInput('Query value', '1'))
  await evaluate(clickTab('Headers'))
  await evaluate(clickText('+ Add header'))
  await evaluate(setInput('Header name', 'X-Smoke'))
  await evaluate(setInput('Header value', 'yes'))
  await clickSend()
  await waitFor(async () => (await snap()).body.includes('x-smoke'), 5000, 'the echoed header')
  const withRows = echo((await snap()).body)
  check('sends the added query parameter', withRows?.url?.includes('smoke=1'), withRows?.url ?? 'none')
  check('sends the added header', withRows?.headers?.['x-smoke'] === 'yes', withRows?.headers?.['x-smoke'] ?? 'none')
  check(
    'resolves an environment variable',
    withRows?.url?.includes('q=environment-token'),
    withRows?.url ?? 'none'
  )
  check(
    'resolves a collection-only variable',
    withRows?.headers?.['x-only'] === 'collection-only',
    withRows?.headers?.['x-only'] ?? 'none'
  )

  console.log('--- 2a. assertions: one green, one red')
  await evaluate(clickTab('Asserts'))
  await evaluate(clickText('+ Add assertion')) // status equals 200: passes against /data
  await evaluate(clickText('+ Add assertion'))
  await evaluate(`(() => {
    const inputs = document.querySelectorAll('input[aria-label="Assertion expected value"]');
    const second = inputs[1];
    if (!second) return false;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(second, '404');
    second.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`)
  await clickSend()
  await waitFor(
    async () =>
      (await evaluate(`document.querySelectorAll('[data-role="assertion"]').length`)) === 2,
    5000,
    'two assertion results'
  )
  const assertions = await evaluate(`(() => ({
    summary: document.querySelector('[data-role="assertions-summary"]')?.textContent.trim() ?? null,
    passed: [...document.querySelectorAll('[data-role="assertion"]')].map(li => li.dataset.passed),
    badge: [...document.querySelectorAll('[role=tab]')].find(t => t.textContent.trim().startsWith('Asserts'))?.textContent.trim() ?? null,
    status: document.querySelector('[data-role="response"] header span')?.textContent.trim() ?? null
  }))()`)
  check(
    'reports how many assertions passed',
    assertions.summary === '1/2 assertions passed',
    assertions.summary ?? 'none'
  )
  check(
    'marks one green and one red',
    assertions.passed.join() === 'true,false',
    assertions.passed.join()
  )
  check('badges the tab with the assertion count', assertions.badge?.includes('2'), assertions.badge ?? 'none')
  check('leaves the status as the first header span', assertions.status === '200', assertions.status ?? 'none')
  await evaluate(`(() => {
    for (const button of [...document.querySelectorAll('button[aria-label="Remove assertion"]')]) button.click();
  })()`)
  await evaluate(clickTab('Params'))

  console.log('--- 2b. environment precedence')
  await evaluate(setSelect('Environment', ''))
  await wait(600)
  await clickSend()
  await waitFor(
    async () => (await snap()).body.includes('q=collection-token'),
    5000,
    'the collection fallback'
  )
  check('collection value applies with no environment', true)

  await evaluate(setSelect('Environment', 'demo/environments/dev.yaml'))
  await wait(600)
  await clickSend()
  await waitFor(
    async () => (await snap()).body.includes('q=environment-token'),
    5000,
    'the environment override'
  )
  check('environment overrides the collection', true)

  console.log('--- 2c. bearer auth from a secret')
  await evaluate(clickText('Variables'))
  await wait(200)
  await evaluate(clickText('+ Add secret'))
  await evaluate(setInput('Secret name', 'smoke-token'))
  await evaluate(setInput('Secret value', 'secret-value'))
  await evaluate(clickText('Save variables'))
  // The button says so once both the variables and the secret are stored; a fixed pause
  // would send the request before the secret the token refers to exists.
  await waitFor(
    async () =>
      (await evaluate(`document.querySelector('[data-role="save-variables"]')?.textContent.trim()`)) ===
      'Saved!',
    5000,
    'the variables to be saved'
  )
  await evaluate(`document.querySelector('[aria-label="Close variables"]')?.click()`)
  await evaluate(clickTab('Auth'))
  await evaluate(setSelect('Auth type', 'bearer'))
  await evaluate(setInput('Bearer token', '{{smoke-token}}'))
  await clickSend()
  await waitFor(async () => (await snap()).body.includes('secret-value'), 5000, 'the bearer token')
  const bearer = echo((await snap()).body)
  check(
    'sends a bearer token only the shell knew',
    bearer?.headers?.authorization === 'Bearer secret-value',
    bearer?.headers?.authorization ?? 'none'
  )

  console.log('--- 2d. OAuth2 client credentials')
  await evaluate(setSelect('Auth type', 'oauth2-client-credentials'))
  await evaluate(setInput('Token URL', `${base}/oauth/token`))
  await evaluate(setInput('Client ID', 'smoke'))
  await evaluate(setInput('Client secret', 'secret'))
  await evaluate(setInput('Scopes', 'read'))
  await clickSend()
  await waitFor(async () => (await snap()).body.includes('smoke-token'), 5000, 'the exchanged token')
  const oauth = echo((await snap()).body)
  check(
    'exchanges client credentials and attaches the token',
    oauth?.headers?.authorization === 'Bearer smoke-token',
    oauth?.headers?.authorization ?? 'none'
  )
  check(
    'sends the client credentials grant',
    lastTokenForm.includes('grant_type=client_credentials') && lastTokenForm.includes('scope=read'),
    lastTokenForm
  )
  await evaluate(setSelect('Auth type', 'none'))

  console.log('--- 2e. a typed credential never reaches the file')
  await evaluate(clickTab('Auth'))
  await evaluate(setSelect('Auth type', 'bearer'))
  await evaluate(setInput('Bearer token', 'typed-secret'))
  await evaluate(`window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true }))`)
  await waitFor(
    async () => readFileSync(savedRequest, 'utf8').includes('{{auth-token-'),
    5000,
    'the credential to be replaced by a reference'
  )
  check(
    'moves a typed credential out of the file',
    !readFileSync(savedRequest, 'utf8').includes('typed-secret'),
    readFileSync(savedRequest, 'utf8')
  )
  await clickSend()
  await waitFor(async () => (await snap()).body.includes('typed-secret'), 5000, 'the restored credential')
  const typed = echo((await snap()).body)
  check(
    'sends the credential from the shell store',
    typed?.headers?.authorization === 'Bearer typed-secret',
    typed?.headers?.authorization ?? 'none'
  )
  await evaluate(setSelect('Auth type', 'none'))

  console.log('--- 3. a form body reaches the server')
  await evaluate(clickTab('Body'))
  await evaluate(setSelect('Body mode', 'form'))
  await evaluate(setMethod('POST'))
  await evaluate(clickText('+ Add field'))
  await evaluate(setInput('Field name', 'a'))
  await evaluate(setInput('Field value', '1'))
  await clickSend()
  await waitFor(async () => (await snap()).body.includes('"a=1"'), 5000, 'the echoed body')
  const posted = echo((await snap()).body)
  check('sends the method', posted?.method === 'POST', posted?.method ?? 'none')
  check('sends the form body', posted?.body === 'a=1', posted?.body ?? 'none')

  console.log('--- 3b. a JSON body typed into CodeMirror')
  // A plain value setter cannot reach CodeMirror, so type through the DevTools input domain
  // after focusing the editor. This is the one path the smoke test cannot drive otherwise.
  await evaluate(setSelect('Body mode', 'json'))
  await wait(200)
  await evaluate(`document.querySelector('[data-role="request"] .cm-content')?.focus()`)
  await cdp('Input.insertText', { text: '{"code":"mirror"}' })
  await wait(300)
  await clickSend()
  await waitFor(async () => (await snap()).body.includes('mirror'), 5000, 'the JSON body')
  const jsonPosted = echo((await snap()).body)
  check(
    'sends the JSON body from the editor',
    jsonPosted?.body === '{"code":"mirror"}',
    jsonPosted?.body ?? 'none'
  )
  check(
    'sets the JSON content type',
    (jsonPosted?.headers?.['content-type'] ?? '').startsWith('application/json'),
    jsonPosted?.headers?.['content-type'] ?? 'none'
  )

  console.log('--- 4. response viewer')
  await evaluate(clickResponseTab('Body'))
  check('pretty-prints JSON by default', (await snap()).body.includes('"from": "'), 'pretty')
  await evaluate(clickResponseView('raw'))
  check('raw view keeps the original spacing', (await snap()).body.includes('"from":"'), 'raw')
  await evaluate(clickResponseTab('Headers'))
  check('lists response headers', (await snap()).paneText.includes('content-type'), 'headers')
  await evaluate(clickResponseTab('Cookies'))
  const cookieText = (await snap()).paneText
  check('shows a Set-Cookie', cookieText.includes('smoke'), cookieText.slice(0, 80))
  check('shows cookie flags', cookieText.includes('HttpOnly'))
  await evaluate(clickResponseTab('Timing'))
  const timingText = (await snap()).paneText
  check('shows the timing breakdown', timingText.includes('DNS') && timingText.includes('Total'))
  await evaluate(clickResponseTab('Body'))

  console.log('--- 5. HTML preview')
  await evaluate(setUrl(`${base}/html`))
  await clickSend()
  await waitFor(async () => (await snap()).paneText.includes('text/html'), 5000, 'the HTML response')
  await evaluate(clickResponseTab('Body'))
  await evaluate(clickResponseView('preview'))
  const frame = await evaluate(`(() => {
    const element = document.querySelector('[data-role="response"] iframe');
    return element
      ? { sandbox: element.getAttribute('sandbox'), srcdoc: element.getAttribute('srcdoc') ?? '' }
      : null;
  })()`)
  check(
    'preview embeds the HTML in a locked-down frame',
    !!frame && frame.sandbox === '' && frame.srcdoc.includes('preview me'),
    JSON.stringify(frame)
  )

  console.log('--- 6. exchange over real HTTPS')
  if (process.env.PING_SMOKE_OFFLINE === '1') {
    console.log('    skipped: PING_SMOKE_OFFLINE is set')
  } else {
    await evaluate(setMethod('GET'))
    await evaluate(setSelect('Body mode', 'none'))
    await evaluate(setUrl('https://jsonplaceholder.typicode.com/todos/1'))
    await clickSend()
    await waitFor(
      async () => (await snap()).body.includes('delectus aut autem'),
      8000,
      'the HTTPS body'
    )
    const secure = await snap()
    check('renders the HTTPS status', secure.status === '200', secure.status ?? 'none')
  }

  console.log('--- 7. cancel an in-flight request')
  await waitFor(async () => (await snap()).sendLabel === 'Send', 3000, 'the idle send button')
  slowStarted = false
  await evaluate(setUrl(`${base}/slow`))
  await clickSend()
  await waitFor(async () => (await snap()).cancelVisible, 3000, 'the cancel button')
  // Only cancel once the request has genuinely reached the server, so the core has it
  // registered. This is what a person does anyway: see it hanging, then click.
  await waitFor(() => slowStarted, 5000, 'the slow request to reach the server')
  const during = await snap()
  check('reports the request in flight', during.sendLabel === 'Sending…', during.sendLabel ?? 'none')
  check('offers cancellation', during.cancelVisible)
  await evaluate(
    `[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Cancel').click()`
  )
  await waitFor(async () => (await snap()).cancelled !== null, 6000, 'the cancelled state')
  const after = await snap()
  check('renders a neutral cancelled state', after.cancelled === 'Request cancelled.', after.cancelled ?? 'none')
  check('does not render cancellation as an error', after.error === null, after.error ?? '')
  check('restores the send button', after.sendLabel === 'Send', after.sendLabel ?? 'none')

  console.log('--- 8. unreachable host')
  await evaluate(setUrl('http://127.0.0.1:1/nope'))
  await clickSend()
  await waitFor(async () => (await snap()).error !== null, 5000, 'the error banner')
  const failed = await snap()
  check('shows an error', failed.error !== null)
  check('does not leak a Java class name', !/Exception/.test(failed.error ?? ''), failed.error ?? '')
  check(
    'does not leak the Electron IPC prefix',
    !/remote method/i.test(failed.error ?? ''),
    failed.error ?? ''
  )

  console.log('--- 9. command palette, theme and tabs')
  const semantics = await evaluate(`(() => {
    const panel = document.getElementById('request-panel');
    const labelledBy = panel?.getAttribute('aria-labelledby');
    const tab = labelledBy ? document.getElementById(labelledBy) : null;
    return {
      role: panel?.getAttribute('role') ?? null,
      labelled: !!tab,
      controls: tab?.getAttribute('aria-controls') === 'request-panel',
      tabbable: tab?.getAttribute('tabindex') === '0'
    };
  })()`)
  check(
    'tabs expose a labelled tabpanel',
    semantics.role === 'tabpanel' && semantics.labelled && semantics.controls && semantics.tabbable,
    JSON.stringify(semantics)
  )

  // Mount a CodeMirror so the editor theme can be observed.
  await evaluate(clickTab('Body'))
  await evaluate(setSelect('Body mode', 'json'))
  await wait(200)
  const editorColor = `getComputedStyle(document.querySelector('[data-role="request"] .cm-content')).color`
  const beforeColor = await evaluate(editorColor)

  const pressCtrlK = `window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))`
  await evaluate(pressCtrlK)
  await waitFor(
    async () => await evaluate(`!!document.querySelector('[data-role="palette"]')`),
    2000,
    'the command palette'
  )
  check('command palette opens on Ctrl-K', true)

  const combobox = await evaluate(`(() => {
    const input = document.querySelector('[data-role="palette"] [role="combobox"]');
    return {
      role: input?.getAttribute('role') ?? null,
      controls: input?.getAttribute('aria-controls') ?? null,
      active: input?.getAttribute('aria-activedescendant') ?? null
    };
  })()`)
  check(
    'palette is a combobox tied to its listbox',
    combobox.role === 'combobox' && combobox.controls === 'palette-listbox' && !!combobox.active,
    JSON.stringify(combobox)
  )

  const typeInPalette = (text) => `(() => {
    const input = document.querySelector('[data-role="palette"] input');
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, ${JSON.stringify(text)});
    input.dispatchEvent(new Event('input', { bubbles: true }));
    return input.value;
  })()`
  const themeOption =
    `[...document.querySelectorAll('[data-role="palette"] [role="option"]')]` +
    `.find(o => o.textContent.includes('Theme:'))`

  await evaluate(typeInPalette('theme'))
  await waitFor(async () => await evaluate(`!!${themeOption}`), 2000, 'the theme command')
  check('palette filters commands', true)

  const initialTheme = await evaluate(`document.documentElement.dataset.theme`)
  await evaluate(`${themeOption}?.click()`)
  await waitFor(
    async () => (await evaluate(`document.documentElement.dataset.theme`)) !== initialTheme,
    2000,
    'the theme to change'
  )
  check('theme command switches the theme', true)
  const afterColor = await evaluate(editorColor)
  check('the editor follows the theme', beforeColor !== afterColor, `${beforeColor} -> ${afterColor}`)

  // Walk the cycle until it returns. Distinct stops prove none is skipped or repeated; the
  // cap keeps a broken cycle from spinning forever.
  const ring = [initialTheme, await evaluate(`document.documentElement.dataset.theme`)]
  const MAX_THEMES = 8
  while (ring[ring.length - 1] !== initialTheme && ring.length <= MAX_THEMES) {
    await evaluate(pressCtrlK)
    await wait(200)
    await evaluate(`${themeOption}?.click()`)
    await wait(200)
    ring.push(await evaluate(`document.documentElement.dataset.theme`))
  }
  const stops = ring.slice(0, -1)
  check(
    'theme command cycles through every theme and returns',
    ring[ring.length - 1] === initialTheme && stops.length >= 2 && new Set(stops).size === stops.length,
    ring.join(' -> ')
  )

  console.log('--- 10. resizable panels')
  const handles = await evaluate(`(() => {
    const separators = [...document.querySelectorAll('[role="separator"]')];
    const section = document.querySelector('[data-role="request"]');
    const handle = section && section.parentElement && section.parentElement.nextElementSibling;
    return {
      count: separators.length,
      orientations: separators.map(s => s.getAttribute('aria-orientation')),
      requestValue: handle ? Number(handle.getAttribute('aria-valuenow')) : null,
      requestFlex: handle ? handle.previousElementSibling.getAttribute('style') : null,
      labelled: !!handle?.getAttribute('aria-label')
    };
  })()`)
  check('renders a handle per split', handles.count === 2, String(handles.count))
  check(
    'handles orient for their axis',
    handles.orientations.join(',') === 'vertical,horizontal',
    handles.orientations.join(',')
  )
  check('the request split reports its size', handles.requestValue === 50, String(handles.requestValue))
  check('the handle is labelled', handles.labelled)

  await evaluate(`(() => {
    const section = document.querySelector('[data-role="request"]');
    const handle = section.parentElement.nextElementSibling;
    handle.focus();
    handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    return true;
  })()`)
  await wait(100)
  const resized = await evaluate(`(() => {
    const section = document.querySelector('[data-role="request"]');
    const handle = section.parentElement.nextElementSibling;
    return {
      value: Number(handle.getAttribute('aria-valuenow')),
      focused: document.activeElement === handle,
      flex: section.parentElement.getAttribute('style')
    };
  })()`)
  check('arrow keys resize the split', resized.value > handles.requestValue, `${handles.requestValue} -> ${resized.value}`)
  check('the handle takes focus', resized.focused)
  check('the pane follows the handle', resized.flex !== handles.requestFlex, `${handles.requestFlex} -> ${resized.flex}`)

  await evaluate(`(() => {
    const section = document.querySelector('[data-role="request"]');
    const handle = section.parentElement.nextElementSibling;
    const rect = handle.getBoundingClientRect();
    handle.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true,
      clientX: rect.left + rect.width / 2,
      clientY: rect.top + rect.height / 2
    }));
    window.dispatchEvent(new PointerEvent('pointermove', {
      bubbles: true,
      clientX: rect.left + rect.width / 2,
      clientY: rect.top - 120
    }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
    return true;
  })()`)
  await wait(100)
  const dragged = await evaluate(`(() => {
    const section = document.querySelector('[data-role="request"]');
    const handle = section.parentElement.nextElementSibling;
    return Number(handle.getAttribute('aria-valuenow'));
  })()`)
  check('dragging the handle resizes the split', dragged < resized.value, `${resized.value} -> ${dragged}`)

  console.log('--- 11. collapse the collections sidebar')
  const sidebarVisible = async () => await evaluate(`!!document.querySelector('[data-role="sidebar"]')`)
  const separatorCount = async () =>
    await evaluate(`document.querySelectorAll('[role="separator"]').length`)
  check('sidebar starts visible', await sidebarVisible())

  await evaluate(
    `document.querySelector('button[aria-label="Hide collections sidebar"]')?.click()`
  )
  await waitFor(async () => !(await sidebarVisible()), 2000, 'the sidebar to collapse')
  check('hides the sidebar', !(await sidebarVisible()))
  check('drops its split handle', (await separatorCount()) === 1, String(await separatorCount()))
  check(
    'keeps the main pane mounted',
    (await evaluate(`!!document.querySelector('input[aria-label="Request URL"]')`))
  )

  await evaluate(
    `document.querySelector('button[aria-label="Show collections sidebar"]')?.click()`
  )
  await waitFor(async () => await sidebarVisible(), 2000, 'the sidebar to return')
  check('shows the sidebar again', await sidebarVisible())
  check('restores both split handles', (await separatorCount()) === 2, String(await separatorCount()))

  console.log('--- 12. a large response does not push the resize handle away')
  // The response body is unbounded content; if its split cell lets that size the layout,
  // the container grows past the window and the handle scrolls out of reach. It must clip.
  // The method is set explicitly: step 3 left it POST and step 6 (skipped offline) is what
  // used to restore GET, so inheriting it made this step's history entry depend on CI mode.
  await evaluate(setMethod('GET'))
  await evaluate(setUrl(`${base}/large`))
  await clickSend()
  await waitFor(async () => (await snap()).status === '200', 8000, 'the large response')
  const layout = await evaluate(`(() => {
    const section = document.querySelector('[data-role="request"]');
    const handle = section.parentElement.nextElementSibling;
    const container = handle.parentElement;
    const viewport = document.documentElement.clientHeight;
    const handleRect = handle.getBoundingClientRect();
    const containerRect = container.getBoundingClientRect();
    return {
      value: Number(handle.getAttribute('aria-valuenow')),
      containerHeight: Math.round(containerRect.height),
      viewport,
      handleTop: Math.round(handleRect.top),
      handleBottom: Math.round(handleRect.bottom),
    };
  })()`)
  check(
    'the split container stays within the viewport',
    layout.containerHeight <= layout.viewport + 1,
    `container ${layout.containerHeight}px, viewport ${layout.viewport}px`
  )
  check(
    'the handle stays on screen',
    layout.handleTop >= 0 && layout.handleBottom <= layout.viewport,
    `handle ${layout.handleTop}..${layout.handleBottom}`
  )

  await evaluate(`(() => {
    const section = document.querySelector('[data-role="request"]');
    const handle = section.parentElement.nextElementSibling;
    const rect = handle.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    handle.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: cx, clientY: cy }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, clientX: cx, clientY: cy - 200 }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, clientX: cx, clientY: cy - 200 }));
    return true;
  })()`)
  await wait(100)
  const grown = await evaluate(`(() => {
    const section = document.querySelector('[data-role="request"]');
    const handle = section.parentElement.nextElementSibling;
    return Number(handle.getAttribute('aria-valuenow'));
  })()`)
  check('the response pane can still be grown', grown < layout.value, `${layout.value} -> ${grown}`)

  console.log('--- 13. request tabs')
  const tabCount = async () =>
    await evaluate(`document.querySelectorAll('[data-role="request-tab"]').length`)
  const activeTabPath = async () =>
    await evaluate(
      `document.querySelector('[data-role="request-tab"][aria-selected="true"]')?.dataset.path ?? null`
    )
  // The workspace has one request open in one tab from the auto-open at startup.
  check('opens the first request in a tab', (await tabCount()) === 1, String(await tabCount()))

  // A second request from the tree opens alongside the first, not in place of it.
  const firstTabUrl = await evaluate(
    `document.querySelector('input[aria-label="Request URL"]')?.value`
  )
  await evaluate(
    `[...document.querySelectorAll('[data-node-type="request"] button')].find(b => b.textContent.includes('Added remotely'))?.click()`
  )
  await acceptPrompt()
  await waitFor(async () => (await tabCount()) === 2, 4000, 'a second tab')
  check('opens a request in a new tab', (await tabCount()) === 2, String(await tabCount()))
  check(
    'the new tab is active',
    (await activeTabPath())?.includes('added.yaml'),
    await activeTabPath()
  )

  // Each tab keeps its own draft: edit this one, switch back, and the first is unchanged.
  await evaluate(setInput('Request URL', `${base}/only-in-second`))
  await evaluate(
    `document.querySelectorAll('[data-role="request-tab"]')[0].click()`
  )
  await wait(200)
  const restoredFirstUrl = await evaluate(
    `document.querySelector('input[aria-label="Request URL"]')?.value`
  )
  check(
    'keeps per-tab draft state',
    restoredFirstUrl === firstTabUrl && restoredFirstUrl !== `${base}/only-in-second`,
    `${firstTabUrl} vs ${restoredFirstUrl}`
  )

  // Double-clicking the tab label opens an inline editor; Enter commits the new name.
  const tabLabel = async () =>
    await evaluate(`document.querySelectorAll('[data-role="request-tab"]')[0].textContent.trim()`)
  await evaluate(`(() => {
    const tab = document.querySelectorAll('[data-role="request-tab"]')[0];
    tab.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    return true;
  })()`)
  await waitFor(
    async () => await evaluate(`!!document.querySelector('input[aria-label="Request name"]')`),
    2000,
    'the inline rename input'
  )
  await evaluate(setInput('Request name', 'Renamed by smoke'))
  await evaluate(
    `document.querySelector('input[aria-label="Request name"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`
  )
  await wait(200)
  check(
    'renames a tab on double-click',
    (await tabLabel()).includes('Renamed by smoke'),
    await tabLabel()
  )
  check(
    'marks the rename unsaved',
    await evaluate(`!!document.querySelector('[data-role="dirty"]')`)
  )

  // Saving the renamed tab writes the new name to the file and rescans the tree directly, so
  // the collection shows it without waiting on the filesystem watcher.
  await evaluate(`document.querySelector('[data-role="save"]').click()`)
  await waitFor(
    async () => readFileSync(savedRequest, 'utf8').includes('name: Renamed by smoke'),
    5000,
    'the rename to reach the file'
  )
  check('writes the new name to disk', readFileSync(savedRequest, 'utf8').includes('name: Renamed by smoke'))
  await waitFor(
    async () => (await sidebarText()).includes('Renamed by smoke'),
    8000,
    'the sidebar to show the new name'
  )
  check('reflects the rename in the collection tree', (await sidebarText()).includes('Renamed by smoke'))
  check(
    'the rename is saved, not dirty',
    !(await evaluate(`!!document.querySelector('[data-role="dirty"]')`))
  )

  // Closing the active tab focuses the remaining one.
  await evaluate(
    `document.querySelectorAll('[data-role="request-tab"]')[1].parentElement.querySelector('button[aria-label^="Close"]').click()`
  )
  await acceptPrompt()
  await waitFor(async () => (await tabCount()) === 1, 3000, 'one tab left')
  check('closes a tab', (await tabCount()) === 1, String(await tabCount()))
  check(
    'focuses the surviving tab',
    (await activeTabPath())?.includes('get.yaml'),
    await activeTabPath()
  )

  // The + button adds a scratch tab.
  await evaluate(`document.querySelector('button[aria-label="New request tab"]').click()`)
  await waitFor(async () => (await tabCount()) === 2, 3000, 'a scratch tab')
  check('adds a scratch tab', (await tabCount()) === 2, String(await tabCount()))
  check(
    'a scratch tab has no file',
    (await activeTabPath()) === null || (await activeTabPath()) === '',
    String(await activeTabPath())
  )

  // In-flight state is per tab: start a slow request in the scratch tab, switch to the
  // saved one, and it must show its own idle state, not the other tab's spinner.
  await evaluate(setUrl(`${base}/slow`))
  await clickSend()
  await waitFor(async () => (await snap()).cancelVisible, 4000, 'the scratch tab to send')
  check('the sending tab reports in flight', (await snap()).sendLabel === 'Sending…')

  await evaluate(`document.querySelectorAll('[data-role="request-tab"]')[0].click()`)
  await wait(200)
  const otherTab = await snap()
  check(
    'another tab keeps its own idle state',
    !otherTab.cancelVisible && otherTab.sendLabel === 'Send',
    `${otherTab.sendLabel}, cancel=${otherTab.cancelVisible}`
  )

  await evaluate(`document.querySelectorAll('[data-role="request-tab"]')[1].click()`)
  await wait(200)
  check('the sending tab is still in flight when refocused', (await snap()).cancelVisible)
  await evaluate(
    `[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Cancel').click()`
  )
  await waitFor(async () => (await snap()).cancelled !== null, 6000, 'the cancel to land')

  // Closing the last tab leaves an empty one rather than a blank window.
  await evaluate(
    `document.querySelectorAll('[data-role="request-tab"]')[1].parentElement.querySelector('button[aria-label^="Close"]').click()`
  )
  await waitFor(async () => (await tabCount()) === 1, 3000, 'the last tab to close')
  check('closing the last tab leaves one behind', (await tabCount()) === 1, String(await tabCount()))

  console.log('--- 14. request history')
  const historyCount = async () =>
    await evaluate(`document.querySelectorAll('[data-role="history-entry"]').length`)
  // Every send above recorded an entry, so the tab has content before this step runs.
  check(
    'the sidebar offers a history tab',
    await evaluate(
      `[...document.querySelectorAll('[role=tab]')].some(t => t.textContent.trim() === 'History')`
    )
  )
  await evaluate(clickText('History'))
  await waitFor(async () => (await historyCount()) > 0, 3000, 'a recorded entry')
  check('lists executed requests', (await historyCount()) > 0, String(await historyCount()))

  const newest = await evaluate(`document.querySelector('[data-role="history-entry"]').textContent`)
  // The newest entry is whichever request ran last, so assert its shape — an HTTP method
  // and an outcome — rather than a specific status that depends on step ordering.
  check(
    'shows the method and an outcome',
    /^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b/.test(newest.trim()) &&
      /(200|cancelled|failed)/.test(newest),
    newest.trim()
  )

  // Search narrows the list to name/method/URL matches; clearing it restores every entry.
  const allEntries = await historyCount()
  const searchUrl = await evaluate(
    `document.querySelector('[data-role="history-entry"]').dataset.url`
  )
  const needle = searchUrl.replace(/^https?:\/\//, '').split(/[?#]/)[0]
  await evaluate(setInput('Search history', needle))
  await wait(100)
  const matched = await historyCount()
  check(
    'filters history by search text',
    matched >= 1 &&
      matched <= allEntries &&
      (await evaluate(
        `[...document.querySelectorAll('[data-role="history-entry"]')].every(
          e => e.dataset.url.includes(${JSON.stringify(needle)})
        )`
      )),
    `${matched}/${allEntries} match ${needle}`
  )
  await evaluate(setInput('Search history', ''))
  await wait(100)
  check('clearing search restores history', (await historyCount()) === allEntries)

  // The list is shell-local; the file lives in the throwaway profile, so its presence
  // proves the main process persisted it rather than the renderer holding state only.
  const persisted = JSON.parse(readFileSync(join(userDataDir, 'history.json'), 'utf8'))
  check('persists history in userData', Array.isArray(persisted) && persisted.length > 0)
  check(
    'never records a literal credential',
    !JSON.stringify(persisted).includes('typed-secret') &&
      !JSON.stringify(persisted).includes('secret-value'),
    'checked for smoke secrets'
  )

  // Selecting an entry restores it. The draft is dirty from earlier steps, so accept the
  // discard prompt the same way a person would.
  const restoreUrl = await evaluate(`document.querySelector('[data-role="history-entry"]').dataset.url`)
  await evaluate(`document.querySelector('[data-role="history-entry"]').click()`)
  await acceptPrompt(1000)
  await wait(300)
  check(
    'restores a request into the editor',
    (await evaluate(`document.querySelector('input[aria-label="Request URL"]')?.value`)) ===
      restoreUrl,
    restoreUrl ?? 'none'
  )

  console.log('--- 15. copy as cURL')
  // Build a request that exercises every part a curl command has to carry.
  await evaluate(setMethod('POST'))
  await evaluate(setUrl(`${base}/curl?existing=1`))
  await evaluate(clickTab('Params'))
  await evaluate(clickText('+ Add parameter'))
  await evaluate(setInput('Query parameter', 'from'))
  await evaluate(setInput('Query value', 'curl'))
  await evaluate(clickTab('Headers'))
  await evaluate(clickText('+ Add header'))
  await evaluate(setInput('Header name', 'X-Curl'))
  await evaluate(setInput('Header value', 'yes'))
  await evaluate(clickTab('Body'))
  await evaluate(setSelect('Body mode', 'form'))
  await evaluate(clickText('+ Add field'))
  await evaluate(setInput('Field name', 'a'))
  await evaluate(setInput('Field value', '1'))
  await wait(150)

  const curl = await evaluate(
    `document.querySelector('[data-role="copy-curl"]')?.dataset.curl ?? ''`
  )
  check('offers a copy-as-cURL control', curl.length > 0, curl.slice(0, 40))
  check(
    'renders the method, URL and query string',
    curl.startsWith(`curl -X POST '`) && curl.includes(`/curl?existing=1&from=curl'`),
    curl.split('\n')[0]
  )
  check('renders the header', curl.includes(`-H 'X-Curl: yes'`), curl)
  check('renders the form body', curl.includes(`--data-urlencode 'a=1'`), curl)

  await evaluate(`document.querySelector('[data-role="copy-curl"]').click()`)
  await waitFor(
    async () =>
      (
        await evaluate(`document.querySelector('[data-role="curl-status"]')?.textContent ?? ''`)
      ).includes('copied'),
    3000,
    'the copy confirmation'
  )
  check('confirms the copy', true)

  console.log('--- 15b. paste a cURL command into the URL bar')
  const paste = (text) => `(() => {
    const input = document.querySelector('input[aria-label="Request URL"]');
    input.focus();
    const data = new DataTransfer();
    data.setData('text/plain', ${JSON.stringify(text)});
    // dispatchEvent returns false when a handler called preventDefault.
    return input.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  })()`
  const importState = () => evaluate(`(() => ({
    url: document.querySelector('input[aria-label="Request URL"]')?.value ?? null,
    method: document.querySelector('select[aria-label="HTTP method"]')?.value ?? null,
    notice: document.querySelector('[data-role="import-notice"]')?.textContent.trim() ?? null,
    error: document.querySelector('[data-role="import-notice"]')?.dataset.error ?? null,
    warnings: [...document.querySelectorAll('[data-role="import-warning"]')].map(w => w.textContent.trim())
  }))()`)

  const plainPasted = await evaluate(paste('https://example.com/just-a-url'))
  check('leaves an ordinary paste alone', plainPasted === true, String(plainPasted))

  const intercepted = await evaluate(
    paste(`curl -X POST '${base}/curl-import?from=paste' -H 'X-Pasted: yes' -H 'Content-Type: application/json' -d '{"n":1}' --cert c.pem`)
  )
  check('intercepts a pasted curl command', intercepted === false, String(intercepted))
  await waitFor(async () => (await importState()).notice?.startsWith('Imported from cURL'), 5000, 'the import notice')
  const imported = await importState()
  check('fills in the URL without the query', imported.url === `${base}/curl-import`, imported.url ?? 'none')
  check('fills in the method', imported.method === 'POST', imported.method ?? 'none')
  check(
    'reports what it could not carry over',
    imported.warnings.length === 1 && imported.warnings[0].includes('--cert'),
    imported.warnings.join(' | ')
  )

  await clickSend()
  await waitForEcho('x-pasted')
  const pasted = echo((await snap()).body)
  check('sends the imported method', pasted?.method === 'POST', pasted?.method ?? 'none')
  check('sends the imported query', pasted?.url?.includes('from=paste'), pasted?.url ?? 'none')
  check('sends the imported header', pasted?.headers?.['x-pasted'] === 'yes', pasted?.headers?.['x-pasted'] ?? 'none')
  check('sends the imported JSON body', pasted?.body === '{"n":1}', pasted?.body ?? 'none')

  const rejected = await evaluate(paste(`curl 'https://unterminated.example`))
  check('takes over a curl paste even when it cannot be read', rejected === false, String(rejected))
  await waitFor(async () => (await importState()).error === 'true', 5000, 'the import error')
  const fallback = await importState()
  check(
    'falls back to pasting the text',
    fallback.url?.includes(`curl 'https://unterminated.example`),
    fallback.url ?? 'none'
  )

  console.log('--- 15c. import a Postman collection')
  await evaluate(clickText('Collections')) // the history section left the other panel showing
  await evaluate(`document.querySelector('button[aria-label="Import collection"]')?.click()`)
  await waitFor(
    async () => await evaluate(`!!document.querySelector('[data-role="import-report"]')`),
    8000,
    'the import report'
  )
  const report = await evaluate(`(() => ({
    collections: [...document.querySelectorAll('[data-role="import-collection"]')].map(e => e.textContent.replace(/\\s+/g, ' ').trim()),
    secrets: document.querySelector('[data-role="import-secrets"]')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
    warnings: [...document.querySelectorAll('[data-role="import-report-warning"]')].map(e => e.textContent.trim())
  }))()`)
  check('reports the created collection', report.collections.join('|') === 'Imported demo: 2 requests', report.collections.join('|'))
  check('reports the credential it moved', report.secrets?.startsWith('1 credential was'), report.secrets ?? 'none')
  check(
    'reports what it could not carry over',
    report.warnings.length === 1 && report.warnings[0].includes('/tmp/payload.bin'),
    report.warnings.join(' | ')
  )
  check('never shows the secret value', !JSON.stringify(report).includes('smoke-import-token'))
  await waitFor(async () => (await sidebarText()).includes('Whoami'), 8000, 'the imported request in the tree')

  const importedFile = readFileSync(join(workspaceDir, 'Imported demo', 'whoami.yaml'), 'utf8')
  check('writes a reference, not the literal', importedFile.includes('{{import-imported-demo-whoami-token}}') && !importedFile.includes('smoke-import-token'), importedFile.split('\n').find((l) => l.includes('token')) ?? 'none')
  check('keeps the script as a note', importedFile.includes('docs:') && importedFile.includes('pm.test'))
  const secretNames = await evaluate(`window.ping.secrets.list()`)
  check('stored the secret by name', secretNames.includes('import-imported-demo-whoami-token'), secretNames.join(', '))

  await evaluate(
    `[...document.querySelectorAll('[data-node-type="request"] button')].find(b => b.textContent.includes('Whoami'))?.click()`
  )
  await waitFor(async () => (await activeTabPath())?.includes('whoami.yaml'), 5000, 'the imported request to open')
  await clickSend()
  await waitForEcho('authorization')
  const whoami = echo((await snap()).body)
  check('resolves the imported collection variable', whoami?.url?.startsWith('/whoami'), whoami?.url ?? 'none')
  check('sends the imported query', whoami?.url?.includes('from=postman'), whoami?.url ?? 'none')
  check('sends the restored secret', whoami?.headers?.authorization === 'Bearer smoke-import-token', whoami?.headers?.authorization ?? 'none')

  // Editing and saving an imported request must not drop its notes.
  await evaluate(setUrl('{{host}}/whoami?from=edited'))
  await evaluate(`document.querySelector('[data-role="save"]')?.click()`)
  await waitFor(
    async () => readFileSync(join(workspaceDir, 'Imported demo', 'whoami.yaml'), 'utf8').includes('edited'),
    5000,
    'the save'
  )
  const saved = readFileSync(join(workspaceDir, 'Imported demo', 'whoami.yaml'), 'utf8')
  check('a save keeps the notes', saved.includes('docs:') && saved.includes('pm.test'), saved.includes('docs:') ? 'has docs' : 'docs dropped')
  check('a save still holds no literal secret', !saved.includes('smoke-import-token'))
  await evaluate(`document.querySelector('button[aria-label="Dismiss import report"]')?.click()`)

  // The generic core channel must not reach the file-writing importer: it would let the
  // renderer choose the root and read back secret values.
  const direct = await evaluate(
    `window.ping.request('import.collection', { root: '/tmp', content: '{}' })`
  )
  check('refuses import.collection over the generic channel', direct.ok === false && /import dialog/.test(direct.error?.message ?? ''), JSON.stringify(direct))

  console.log('--- 15d. capture a value and use it in the next request')
  await evaluate(`document.querySelector('button[aria-label="New request tab"]').click()`)
  await waitFor(
    async () => (await evaluate(`document.querySelectorAll('[role=tab][data-path]').length`)) >= 2,
    3000,
    'a scratch tab'
  )
  await evaluate(setUrl(`${base}/capture-source`))
  await evaluate(setMethod('GET'))
  await evaluate(clickTab('Capture'))
  await evaluate(clickText('+ Add capture'))
  await evaluate(setInput('Capture variable name', 'who'))
  await evaluate(setInput('Capture target', '$.from'))
  await evaluate(clickText('+ Add capture'))
  await evaluate(setInput('Capture variable name', 'gone'))
  await evaluate(setInput('Capture target', '$.nope'))
  await clickSend()
  await waitFor(
    async () => (await evaluate(`document.querySelectorAll('[data-role="capture"]').length`)) === 2,
    5000,
    'the capture results'
  )
  const captures = await evaluate(`(() => ({
    text: document.querySelector('[data-role="captures"]')?.textContent.replace(/\\s+/g, ' ').trim() ?? '',
    found: [...document.querySelectorAll('[data-role="capture"]')].map(c => c.dataset.found),
    curl: document.querySelector('[data-role="copy-curl"]')?.dataset.curl ?? ''
  }))()`)
  check('reports one hit and one miss', captures.found.join() === 'true,false', captures.found.join())
  check('names the captures', captures.text.includes('who') && captures.text.includes('gone'), captures.text)
  check('explains a miss', captures.text.includes('$.nope matched nothing'), captures.text)
  check('never shows the captured value', !captures.text.includes('local test server'), captures.text)
  const names = await evaluate(`window.ping.runtime.list()`)
  check('holds the hit and not the miss', names.join() === 'who', names.join())

  await evaluate(clickTab('Headers'))
  await evaluate(clickText('+ Add header'))
  await evaluate(setInput('Header name', 'X-Captured'))
  await evaluate(setInput('Header value', '{{who}}'))
  await evaluate(clickText('+ Add header'))
  await evaluate(setInput('Header name', 'X-Missed'))
  await evaluate(setInput('Header value', '{{gone}}'))
  await evaluate(setUrl(`${base}/uses-capture`))
  await clickSend()
  await waitFor(async () => (await snap()).body.includes('x-captured'), 5000, 'the echoed request')
  const used = echo((await snap()).body)
  check('sends the captured value in the next request', used?.headers?.['x-captured'] === 'local test server', used?.headers?.['x-captured'] ?? 'none')
  check('sends a missed capture as written, not empty', used?.headers?.['x-missed'] === '{{gone}}', used?.headers?.['x-missed'] ?? 'none')
  const exported = await evaluate(`document.querySelector('[data-role="copy-curl"]')?.dataset.curl ?? ''`)
  check('keeps the value out of the copied cURL', exported.includes('{{who}}') && !exported.includes('local test server'), exported.slice(0, 120))

  // The Variables panel lists names only, and Clear empties the shell's map.
  await evaluate(clickText('Variables'))
  await waitFor(
    async () => (await evaluate(`document.querySelectorAll('[data-role="runtime-name"]').length`)) === 1,
    5000,
    'the runtime section'
  )
  const runtimeText = await evaluate(`document.querySelector('[data-role="runtime"]').textContent`)
  check('lists the runtime name but not its value', runtimeText.includes('who') && !runtimeText.includes('local test server'), runtimeText.replace(/\s+/g, ' ').slice(0, 120))
  await evaluate(`document.querySelector('button[aria-label="Clear runtime variables"]').click()`)
  await waitFor(async () => (await evaluate(`window.ping.runtime.list()`)).length === 0, 5000, 'Clear')
  check('clears the runtime variables', true)
  await evaluate(clickText('Variables'))

  console.log('--- 15e. rename, duplicate, move and filter from the sidebar')
  const row = (path) => `document.querySelector('[data-path="${path}"][data-node-type]')`
  const tabPaths = () =>
    evaluate(`[...document.querySelectorAll('[data-role="request-tab"]')].map(t => t.dataset.path)`)
  const dirtyShown = () => evaluate(`!!document.querySelector('[data-role="dirty"]')`)
  const clickButton = (label) =>
    evaluate(`(() => { const b = document.querySelector('button[aria-label="${label}"]'); if (!b) return false; b.click(); return true })()`)
  const rowExists = (path) => evaluate(`!!${row(path)}`)
  // The tree re-renders after a rescan, so a button may not exist yet: wait for it, then click.
  const clickWhenReady = async (label) => {
    await waitFor(async () => await evaluate(`!!document.querySelector('button[aria-label="${label}"]')`), 5000, `the ${label} button`)
    await clickButton(label)
    await waitFor(async () => await evaluate(`!!document.querySelector('input[aria-label="${label}"]')`), 5000, `the ${label} input`)
  }
  const drag = (fromPath, toPath) => evaluate(`(() => {
    const from = ${row(fromPath)}; const to = ${row(toPath)};
    if (!from || !to) return false;
    const data = new DataTransfer();
    const fire = (el, type) => el.dispatchEvent(new DragEvent(type, { dataTransfer: data, bubbles: true, cancelable: true }));
    fire(from, 'dragstart'); fire(to, 'dragover'); fire(to, 'drop'); fire(from, 'dragend');
    return true;
  })()`)

  // A request with an unsaved edit is renamed from the tree: the tab must follow, dirty and intact.
  await clickButton('New request in demo')
  await waitFor(async () => (await tabPaths()).includes('demo/new-request.yaml'), 5000, 'the new request tab')
  await evaluate(setUrl(`${base}/renamed`))
  check('starts dirty', await dirtyShown())
  await clickButton('Rename New request')
  await evaluate(setInput('Rename New request', 'Ping rename'))
  await evaluate(`document.querySelector('input[aria-label="Rename New request"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`)
  await waitFor(async () => (await tabPaths()).includes('demo/ping-rename.yaml'), 5000, 'the tab to follow the rename')
  check('the tab followed the file', !(await tabPaths()).includes('demo/new-request.yaml'), (await tabPaths()).join(', '))
  check('the file moved on disk', existsSync(join(workspaceDir, 'demo', 'ping-rename.yaml')) && !existsSync(join(workspaceDir, 'demo', 'new-request.yaml')))
  check('the rename is on disk', readFileSync(join(workspaceDir, 'demo', 'ping-rename.yaml'), 'utf8').includes('name: Ping rename'))
  check('keeps the unsaved edit', (await evaluate(`document.querySelector('input[aria-label="Request URL"]').value`)) === `${base}/renamed`)
  check('is still dirty', await dirtyShown())
  await evaluate(`document.querySelector('[data-role="save"]')?.click()`)
  await waitFor(async () => readFileSync(join(workspaceDir, 'demo', 'ping-rename.yaml'), 'utf8').includes('/renamed'), 5000, 'the save')
  check('a save writes to the new path, not the old one', !existsSync(join(workspaceDir, 'demo', 'new-request.yaml')))
  // The bytes land on disk before the renderer's own write resolves and clears the marker,
  // so seeing the file is not proof the UI has caught up.
  await waitFor(async () => !(await dirtyShown()), 5000, 'the dirty marker to clear')
  check('a save clears the dirty marker', true)

  // Duplicate, create a folder, then drag the request into it.
  await clickButton('Duplicate Ping rename')
  await waitFor(async () => await rowExists('demo/ping-rename-copy.yaml'), 5000, 'the copy')
  check('duplicates next to the original', readFileSync(join(workspaceDir, 'demo', 'ping-rename-copy.yaml'), 'utf8').includes('name: Ping rename copy'))
  await clickButton('New folder in demo')
  await evaluate(setInput('New folder name', 'Archive'))
  await evaluate(`document.querySelector('input[aria-label="New folder name"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`)
  await waitFor(async () => await rowExists('demo/Archive'), 5000, 'the new folder')
  check('creates a folder on disk', existsSync(join(workspaceDir, 'demo', 'Archive')))

  await drag('demo/ping-rename.yaml', 'demo/Archive')
  await waitFor(async () => (await tabPaths()).includes('demo/Archive/ping-rename.yaml'), 5000, 'the tab to follow the move')
  check('dragging moves the file', existsSync(join(workspaceDir, 'demo', 'Archive', 'ping-rename.yaml')) && !existsSync(join(workspaceDir, 'demo', 'ping-rename.yaml')))
  await drag('demo', 'demo/Archive')
  check('a collection cannot be dropped anywhere', existsSync(join(workspaceDir, 'demo', 'get.yaml')))

  // Renaming a folder re-points every tab inside it.
  await clickWhenReady('Rename Archive')
  await evaluate(setInput('Rename Archive', 'Old stuff'))
  await evaluate(`document.querySelector('input[aria-label="Rename Archive"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`)
  await waitFor(async () => (await tabPaths()).includes('demo/Old stuff/ping-rename.yaml'), 5000, 'the tab to follow the folder rename')
  check('renaming a folder moves it on disk', existsSync(join(workspaceDir, 'demo', 'Old stuff', 'ping-rename.yaml')) && !existsSync(join(workspaceDir, 'demo', 'Archive')))
  check('a reserved folder name is sanitised, not obeyed', await (async () => {
    await clickWhenReady('Rename Old stuff')
    await evaluate(setInput('Rename Old stuff', 'environments'))
    await evaluate(`document.querySelector('input[aria-label="Rename Old stuff"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`)
    await waitFor(async () => existsSync(join(workspaceDir, 'demo', 'environments folder')), 5000, 'the sanitised rename')
    return existsSync(join(workspaceDir, 'demo', 'environments folder'))
  })())

  // The filter narrows the tree to matches and their ancestors.
  await evaluate(setInput('Filter requests', 'copy'))
  await waitFor(
    async () => (await evaluate(`document.querySelectorAll('[data-node-type="request"]').length`)) === 1,
    5000,
    'the filtered tree'
  )
  const filtered = await evaluate(`[...document.querySelectorAll('[data-node-type="request"]')].map(r => r.dataset.path)`)
  check('shows only the matching request', filtered.join() === 'demo/ping-rename-copy.yaml', filtered.join())
  check('keeps the ancestor visible', await rowExists('demo'))
  await evaluate(setInput('Filter requests', 'zzz-no-such'))
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="filter-empty"]')`), 5000, 'the empty state')
  check('says when nothing matches', true)
  await evaluate(setInput('Filter requests', ''))
  await waitFor(async () => (await evaluate(`document.querySelectorAll('[data-node-type="request"]').length`)) > 1, 5000, 'the full tree')
  check('clearing the filter restores the tree', true)

  console.log('--- 15f. markdown notes on a request and on a collection')
  const setTextarea = (label, text) => `(() => {
    const area = document.querySelector('textarea[aria-label="${label}"]');
    if (!area) return false;
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
    setter.call(area, ${JSON.stringify(text)});
    area.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`
  const notesView = (label, mode) => `(() => {
    const tab = [...document.querySelectorAll('[role=tablist][aria-label="${label} view"] [role=tab]')].find(t => t.textContent.trim().toLowerCase() === '${mode}');
    if (!tab) return false;
    tab.click();
    return true;
  })()`

  const hostile = [
    '# Title',
    '',
    '- one',
    '- two',
    '',
    '[site](https://example.com)',
    '',
    '[bad](javascript:alert(1))',
    '',
    '<script>window.__pwned = 1</script>',
    '',
    '<img src=x onerror="window.__pwned = 2">'
  ].join('\n')

  await evaluate(clickTab('Docs'))
  check('an empty request opens ready to type', await evaluate(setTextarea('Request notes', hostile)))
  check('notes make the request dirty', await dirtyShown())
  await evaluate(notesView('Request notes', 'preview'))
  const preview = await evaluate(`(() => {
    const p = document.querySelector('[data-role="docs-preview"]');
    return {
      heading: p?.querySelector('h1')?.textContent ?? null,
      items: p?.querySelectorAll('li').length ?? 0,
      link: p?.querySelector('a[href="https://example.com"]')?.getAttribute('rel') ?? null,
      jsLinks: p?.querySelectorAll('a[href^="javascript" i]').length ?? -1,
      scripts: p?.querySelectorAll('script, img, iframe').length ?? -1,
      pwned: window.__pwned ?? null,
      text: p?.textContent ?? ''
    };
  })()`)
  check('renders headings, lists and links', preview.heading === 'Title' && preview.items === 2 && preview.link === 'noopener noreferrer', JSON.stringify(preview).slice(0, 120))
  check('a javascript: link is not a link', preview.jsLinks === 0, String(preview.jsLinks))
  check('raw HTML is shown as text, never run', preview.scripts === 0 && preview.pwned === null && preview.text.includes('<script>'), `elements=${preview.scripts} pwned=${preview.pwned}`)
  await evaluate(`document.querySelector('[data-role="save"]')?.click()`)
  await waitFor(async () => readFileSync(join(workspaceDir, 'demo', 'ping-rename-copy.yaml'), 'utf8').includes('docs:'), 5000, 'the notes on disk')
  check('saves the notes with the request', readFileSync(join(workspaceDir, 'demo', 'ping-rename-copy.yaml'), 'utf8').includes('# Title'))
  await waitFor(async () => !(await dirtyShown()), 5000, 'the dirty marker to clear again')
  check('a save clears the dirty marker again', true)
  check('the Docs tab is badged', await evaluate(`[...document.querySelectorAll('[role=tab]')].some(t => t.textContent.trim().startsWith('Docs') && t.textContent.includes('•'))`))

  await evaluate(clickText('Variables'))
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="toggle-notes"]')`), 5000, 'the notes toggle')
  check('the collection notes are folded behind an icon', await evaluate(`!document.querySelector('textarea[aria-label="Collection notes"]')`))
  await evaluate(`document.querySelector('[data-role="toggle-notes"]').click()`)
  await waitFor(async () => await evaluate(`!!document.querySelector('textarea[aria-label="Collection notes"]')`), 5000, 'the collection notes editor')
  const saveLabel = `document.querySelector('[data-role="save-variables"]')?.textContent.trim()`
  // The file appears long before the panel has finished saving: the save ends by reloading the
  // collection, and that reload writes the stored notes back over whatever is in the field. So
  // an edit typed between the two is reverted under it, and the next save writes the old text —
  // which is what "timed out waiting for the notes to clear" looked like. The button says
  // "Saved!" only once the reload has landed, so that is the wait; the fade back to the label
  // is waited on too, or the confirmation from one save is still on screen for the next check.
  const saveVariables = async () => {
    await evaluate(`document.querySelector('[data-role="variables"] footer button').click()`)
    await waitFor(async () => (await evaluate(saveLabel)) === 'Saved!', 5000, 'the variables to be saved')
    await waitFor(async () => (await evaluate(saveLabel)) === 'Save variables', 5000, 'the confirmation to fade')
  }

  await evaluate(setTextarea('Collection notes', '## Demo API\n\nUse the **dev** environment.'))
  await saveVariables()
  await waitFor(async () => readFileSync(join(workspaceDir, 'demo', 'collection.yaml'), 'utf8').includes('docs:'), 5000, 'the collection notes on disk')
  const collectionYaml = readFileSync(join(workspaceDir, 'demo', 'collection.yaml'), 'utf8')
  check('saves collection notes beside the variables', collectionYaml.includes('Use the **dev** environment') && collectionYaml.includes('collection-token'), collectionYaml.replace(/\n/g, ' | ').slice(0, 140))
  const reloaded = await evaluate(`window.ping.request('vars.catalog', { collection: 'demo' })`)
  check('reads them back', reloaded.ok && reloaded.value.docs?.startsWith('## Demo API'), JSON.stringify(reloaded).slice(0, 100))

  await evaluate(setTextarea('Collection notes', ''))
  await saveVariables()
  await waitFor(async () => !readFileSync(join(workspaceDir, 'demo', 'collection.yaml'), 'utf8').includes('docs:'), 5000, 'the notes to clear')
  check('clearing the notes removes them from the file', true)

  await evaluate(`document.querySelector('[data-role="variables"] footer button').click()`)
  await waitFor(async () => (await evaluate(saveLabel)) === 'Saved!', 3000, 'the save confirmation')
  check('a save says so on the button', true)
  await waitFor(async () => (await evaluate(saveLabel)) === 'Save variables', 5000, 'the confirmation to fade')
  check('the confirmation fades back to the label', true)

  const icons = await evaluate(`document.querySelectorAll('[data-role="variables"] [data-role="info-hint"]').length`)
  check('every section explains itself behind an icon', icons >= 4, String(icons))
  check('no section spells its description out', await evaluate(`!document.querySelector('[data-role="variables"] [data-role="hint"]')`))
  await evaluate(`document.querySelector('[data-role="variables"] [data-role="info-hint"]').click()`)
  await waitFor(async () => await evaluate(`(document.querySelector('[data-role="variables"] [data-role="hint"]')?.textContent.trim().length ?? 0) > 0`), 3000, 'the description')
  check('the icon opens the description', true)
  await evaluate(`document.querySelector('[data-role="variables"] [data-role="info-hint"]').click()`)
  await waitFor(async () => await evaluate(`!document.querySelector('[data-role="variables"] [data-role="hint"]')`), 3000, 'the description to close')
  await evaluate(clickText('Variables'))

  console.log('--- 15g. upload files: multipart parts and a binary body')
  await clickButton('New request in demo')
  await waitFor(async () => (await tabPaths()).includes('demo/new-request.yaml'), 5000, 'the upload tab')
  await evaluate(setUrl(`${base}/upload`))
  await evaluate(setMethod('POST'))
  await evaluate(clickTab('Body'))
  await evaluate(setSelect('Body mode', 'multipart'))
  await evaluate(clickText('+ Add field'))
  await evaluate(setInput('Field name', 'note'))
  await evaluate(setInput('Field value', 'hello'))
  await evaluate(clickText('+ Add file'))
  await evaluate(setInput('Field name', 'logo'))
  const clickChoose = () => evaluate(`(() => { const all = [...document.querySelectorAll('button[aria-label^="Choose "]')]; const b = all[all.length - 1]; if (!b) return false; b.click(); return true })()`)
  await clickChoose()
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="chosen-file"]')`), 5000, 'the chosen file')
  check('shows the chosen file by name', (await evaluate(`document.querySelector('[data-role="chosen-file"]').textContent.trim()`)) === outsideUpload.split(/[\\/]/).pop())
  await clickSend()
  await waitFor(async () => (lastUpload?.headers['content-type'] ?? '').startsWith('multipart/form-data'), 5000, 'the upload')
  const multipartEcho = lastUpload
  const sent = multipartEcho.body
  const boundary = multipartEcho.headers['content-type'].split('boundary=')[1]
  const marker = Buffer.from('name="logo"')
  const at = sent.indexOf(marker)
  const start = sent.indexOf(Buffer.from('\r\n\r\n'), at) + 4
  const end = sent.indexOf(Buffer.from(`\r\n--${boundary}`), start)
  check('a multipart file part arrives byte-identical', sent.subarray(start, end).equals(uploadBytes(7)), `${end - start} bytes`)
  check('the text field arrives too', sent.toString('latin1').includes('name="note"\r\n\r\nhello'))
  check('the length is fixed, not chunked', multipartEcho.headers['content-length'] === String(sent.length) && !multipartEcho.headers['transfer-encoding'])

  // A file inside the collection is stored relative to it, so the collection stays portable.
  await evaluate(setSelect('Body mode', 'file'))
  await waitFor(async () => await evaluate(`!!document.querySelector('button[aria-label="Choose body file"]')`), 5000, 'the binary body editor')
  await evaluate(`document.querySelector('button[aria-label="Choose body file"]').click()`)
  await waitFor(async () => (await evaluate(`document.querySelector('[data-role="chosen-file"]')?.textContent.trim()`)) === 'logo.bin', 5000, 'the inside file')
  await clickSend()
  await waitFor(async () => lastUpload?.headers['content-type'] === 'application/octet-stream', 5000, 'the binary upload')
  const binaryEcho = lastUpload
  check('a binary body is the file byte for byte', binaryEcho.body.equals(uploadBytes(9)))
  check('a binary body defaults to octet-stream', binaryEcho.headers['content-type'] === 'application/octet-stream', binaryEcho.headers['content-type'])
  const fileCurl = await evaluate(`document.querySelector('[data-role="copy-curl"]')?.dataset.curl ?? ''`)
  check('copied cURL points at the file', fileCurl.includes("--data-binary '@fixtures/logo.bin'"), fileCurl.slice(-120))

  await evaluate(`document.querySelector('[data-role="save"]')?.click()`)
  await waitFor(async () => readFileSync(join(workspaceDir, 'demo', 'new-request.yaml'), 'utf8').includes('type: file'), 5000, 'the save')
  const uploadYaml = readFileSync(join(workspaceDir, 'demo', 'new-request.yaml'), 'utf8')
  check('a file inside the collection is saved relative to it', uploadYaml.includes('file: fixtures/logo.bin'), uploadYaml.split('\n').filter((l) => l.includes('file')).join(' | '))

  // The shell decides what the core may read.
  const attempt = (body, extra = {}) =>
    evaluate(`window.ping.request('http.send', ${JSON.stringify({ url: `${base}/upload`, method: 'POST', body, ...extra })})`)
  const ungranted = await attempt({ type: 'file', file: process.platform === 'win32' ? 'C:\\Windows\\win.ini' : '/etc/hostname' })
  check('refuses a path the user never chose', ungranted.ok === false && /not chosen in this session/.test(ungranted.error?.message ?? ''), JSON.stringify(ungranted).slice(0, 140))
  const escaping = await attempt({ type: 'file', file: '../secret.txt' }, { collection: 'demo' })
  check('refuses a relative path that leaves the collection', escaping.ok === false && /outside the collection/.test(escaping.error?.message ?? ''), JSON.stringify(escaping).slice(0, 140))
  const spoofed = await attempt({ type: 'file', file: 'demo/get.yaml' }, { filesBase: workspaceDir })
  check('ignores a base the renderer invents', spoofed.ok === false, JSON.stringify(spoofed).slice(0, 140))
  const partRefused = await attempt({ type: 'multipart', fields: [{ name: 'f', file: process.platform === 'win32' ? 'C:\\Windows\\win.ini' : '/etc/hostname', enabled: true }] })
  check('refuses an ungranted file part too', partRefused.ok === false && /not chosen in this session/.test(partRefused.error?.message ?? ''))
  const disabledIgnored = await attempt({ type: 'multipart', fields: [{ name: 'f', file: '/etc/hostname', enabled: false }, { name: 'a', value: '1', enabled: true }] })
  check('a disabled file row is not checked or read', disabledIgnored.ok === true, JSON.stringify(disabledIgnored).slice(0, 140))
  const granted = await attempt({ type: 'file', file: outsideUpload })
  check('a path the dialog chose stays readable this session', granted.ok === true, JSON.stringify(granted).slice(0, 140))
  const insideOk = await attempt({ type: 'file', file: 'fixtures/logo.bin' }, { collection: 'demo' })
  check('a relative path in the collection needs no grant', insideOk.ok === true, JSON.stringify(insideOk).slice(0, 140))

  console.log('--- 15h. the cookie jar')
  const DEV = 'demo/environments/dev.yaml'
  const OTHER = 'demo/environments/other.yaml'
  const sendRaw = async (extra = {}) => {
    const result = await evaluate(`window.ping.request('http.send', ${JSON.stringify({ url: `${base}/data`, method: 'GET', collection: 'demo', environment: DEV, ...extra })})`)
    return { ok: result.ok, echo: result.ok ? echo(result.value.body.content) : null, error: result.error }
  }
  await evaluate(`window.ping.cookies.clear('demo', '${DEV}')`)
  await evaluate(`window.ping.cookies.clear('demo', '${OTHER}')`)

  const sent1 = await sendRaw()
  check('the first request carries no cookie', sent1.ok && sent1.echo?.headers?.cookie === undefined, String(sent1.echo?.headers?.cookie))
  const sent2 = await sendRaw()
  check('the next request carries the cookie the first one set', sent2.echo?.headers?.cookie === 'smoke=yes', String(sent2.echo?.headers?.cookie))
  const otherEnv = await sendRaw({ environment: OTHER })
  check('another environment does not see it', otherEnv.echo?.headers?.cookie === undefined, String(otherEnv.echo?.headers?.cookie))
  const explicit = await sendRaw({ headers: [{ name: 'Cookie', value: 'manual=1', enabled: true }] })
  check('an explicit Cookie header replaces the jar', explicit.echo?.headers?.cookie === 'manual=1', String(explicit.echo?.headers?.cookie))
  const optedOut = await sendRaw({ cookies: false })
  check('a request can opt out of the jar', optedOut.echo?.headers?.cookie === undefined, String(optedOut.echo?.headers?.cookie))
  // The dev scope holds a cookie; asking for it by name from another environment must not reach it.
  await evaluate(`window.ping.cookies.clear('demo', '${OTHER}')`)
  const invented = await sendRaw({ cookieScope: `${workspaceDir}|demo|${DEV}`, environment: OTHER })
  check('ignores a scope the renderer invents', invented.echo?.headers?.cookie === undefined, String(invented.echo?.headers?.cookie))

  const listed = await evaluate(`window.ping.cookies.list('demo', '${DEV}')`)
  check('lists the stored cookie', listed.length === 1 && listed[0].name === 'smoke' && listed[0].httpOnly === true, JSON.stringify(listed))
  check('the list never carries a value', !JSON.stringify(listed).includes('yes') && listed.every((c) => !('value' in c)), JSON.stringify(listed))

  await evaluate(clickText('Variables'))
  await waitFor(async () => (await evaluate(`document.querySelectorAll('[data-role="cookie"]').length`)) === 1, 5000, 'the cookie list')
  const jarText = await evaluate(`document.querySelector('[data-role="cookies"]').textContent.replace(/\\s+/g, ' ')`)
  check('the panel shows the name but not the value', jarText.includes('smoke') && !jarText.includes('=yes') && !jarText.includes('yes;'), jarText.slice(0, 160))
  await evaluate(`document.querySelector('button[aria-label="Clear cookies"]').click()`)
  await waitFor(async () => (await evaluate(`window.ping.cookies.list('demo', '${DEV}')`)).length === 0, 5000, 'Clear')
  check('Clear empties the jar', true)

  // The per-request switch is saved with the request.
  await evaluate(clickTab('Settings'))
  await evaluate(`document.querySelector('input[aria-label="Use the cookie jar"]').click()`)
  await evaluate(`document.querySelector('[data-role="save"]')?.click()`)
  await waitFor(async () => readFileSync(join(workspaceDir, 'demo', 'new-request.yaml'), 'utf8').includes('cookies: false'), 5000, 'the setting on disk')
  check('the opt-out is saved in the request file', true)
  await clickSend()
  await waitFor(async () => (await snap()).status === '200', 5000, 'an opted-out send')
  check('an opted-out send stores nothing', (await evaluate(`window.ping.cookies.list('demo', '${DEV}')`)).length === 0)
  await evaluate(clickText('Variables'))

  console.log('--- 15i. network settings')
  const proxyPassword = 'smoke-proxy-pw-7731'
  const fillNetwork = (role, value) => `(() => {
    const input = document.querySelector('[data-role="${role}"]');
    if (!input) return null;
    const proto = input instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(input, ${JSON.stringify(value)});
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
    return input.value;
  })()`
  await evaluate(pressCtrlK)
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="palette"]')`), 2000, 'the command palette')
  await evaluate(typeInPalette('network'))
  const networkOption =
    `[...document.querySelectorAll('[data-role="palette"] [role="option"]')].find(o => o.textContent.includes('Network settings'))`
  await waitFor(async () => await evaluate(`!!${networkOption}`), 2000, 'the network command')
  await evaluate(`${networkOption}?.click()`)
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="network-dialog"]')`), 3000, 'the network dialog')
  await waitFor(async () => !(await evaluate(`document.querySelector('[data-role="network-mode"]').disabled`)), 3000, 'the dialog to load')

  await evaluate(fillNetwork('network-mode', 'manual'))
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="network-url"]')`), 2000, 'the manual fields')
  await evaluate(fillNetwork('network-url', 'socks5://127.0.0.1:1080'))
  await evaluate(`document.querySelector('[data-role="network-save"]').click()`)
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="network-error"]')`), 3000, 'a validation error')
  const socksError = await evaluate(`document.querySelector('[data-role="network-error"]').textContent`)
  check('refuses a SOCKS proxy with a reason', /SOCKS/.test(socksError), socksError)
  check('the dialog stays open on an error', await evaluate(`!!document.querySelector('[data-role="network-dialog"]')`))

  await evaluate(fillNetwork('network-url', `127.0.0.1:${PROXY_PORT}`))
  await evaluate(fillNetwork('network-username', 'smoke-user'))
  await evaluate(fillNetwork('network-password', proxyPassword))
  await evaluate(`document.querySelector('[data-role="network-save"]').click()`)
  await waitFor(async () => !(await evaluate(`!!document.querySelector('[data-role="network-dialog"]')`)), 3000, 'the dialog to close')
  check('saves the proxy settings', true)

  const viaProxy = await sendRaw({ url: 'http://origin.invalid/data' })
  check('a request goes through the configured proxy', viaProxy.ok, JSON.stringify(viaProxy).slice(0, 160))
  const lastProxied = proxied[proxied.length - 1]
  check('the proxy was asked for the origin', lastProxied?.target === 'http://origin.invalid/data', JSON.stringify(lastProxied))
  check(
    'the proxy was shown its credentials',
    lastProxied?.auth === 'Basic ' + Buffer.from('smoke-user:' + proxyPassword).toString('base64'),
    String(lastProxied?.auth)
  )

  const shown = await evaluate(`window.ping.network.get()`)
  check('the settings come back without the password', shown.proxy.mode === 'manual' && shown.proxy.hasPassword === true && !JSON.stringify(shown).includes(proxyPassword), JSON.stringify(shown))
  const networkFile = readFileSync(join(userDataDir, 'network.json'), 'utf8')
  check('the password is not stored in the clear', !networkFile.includes(proxyPassword), networkFile.slice(0, 200))

  // Only the user's saved settings choose a proxy. Turn it off, then try to sneak one in.
  await evaluate(`window.ping.network.set({ proxy: { mode: 'none', url: '', username: '', bypass: '' } })`)
  const before = proxied.length
  const goneDirect = await sendRaw()
  check('with no proxy a request goes direct', goneDirect.ok && !!goneDirect.echo?.headers && proxied.length === before, JSON.stringify(goneDirect).slice(0, 120))
  const sneaked = await sendRaw({ network: { proxy: { mode: 'manual', url: `127.0.0.1:${PROXY_PORT}` } } })
  check('ignores a proxy the renderer invents', sneaked.ok && proxied.length === before, `${proxied.length} vs ${before}`)
  const kept = await evaluate(`window.ping.network.get()`)
  check('a password survives switching the proxy off', kept.proxy.hasPassword === true)

  // A bypassed host goes direct even with the proxy on.
  await evaluate(`window.ping.network.set({ proxy: { mode: 'manual', url: '127.0.0.1:${PROXY_PORT}', username: 'smoke-user', bypass: '127.0.0.1' } })`)
  const bypassed = await sendRaw()
  check('a bypassed host is not proxied', bypassed.ok && !!bypassed.echo?.headers && proxied.length === before, `${proxied.length} vs ${before}`)
  await evaluate(`window.ping.network.set({ proxy: { mode: 'none', url: '', username: '', bypass: '' }, password: '' })`)
  check('a saved password can be removed', (await evaluate(`window.ping.network.get()`)).proxy.hasPassword === false)

  console.log('--- 15j. client certificates')
  const mtlsUrl = `https://127.0.0.1:${MTLS_PORT}/who`
  const mtlsSend = async () => {
    const result = await evaluate(`window.ping.request('http.send', ${JSON.stringify({ url: mtlsUrl, method: 'GET', verifyTls: false, timeoutMs: 8000 })})`)
    return { ok: result.ok, body: result.ok ? result.value.body.content : null, message: result.error?.message ?? '' }
  }
  const withoutCert = await mtlsSend()
  check('a server requiring a certificate refuses a request without one', !withoutCert.ok, JSON.stringify(withoutCert))
  check('and says what is probably missing', /client certificate/.test(withoutCert.message), withoutCert.message)

  await evaluate(pressCtrlK)
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="palette"]')`), 2000, 'the command palette')
  await evaluate(typeInPalette('network'))
  await waitFor(async () => await evaluate(`!!${networkOption}`), 2000, 'the network command')
  await evaluate(`${networkOption}?.click()`)
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="network-certs"]')`), 3000, 'the certificates section')
  await waitFor(async () => !(await evaluate(`document.querySelector('[data-role="network-cert-add"]').disabled`)), 3000, 'the dialog to load')

  await evaluate(fillNetwork('network-cert-host', ''))
  await evaluate(`document.querySelector('[data-role="network-cert-add"]').click()`)
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="network-cert-error"]')`), 3000, 'a validation error')
  check('a certificate needs a host', /host/i.test(await evaluate(`document.querySelector('[data-role="network-cert-error"]').textContent`)))

  await evaluate(fillNetwork('network-cert-host', '127.0.0.1'))
  await evaluate(fillNetwork('network-cert-passphrase', 'clientpw'))
  await evaluate(`document.querySelector('[data-role="network-cert-add"]').click()`)
  await waitFor(async () => (await evaluate(`document.querySelectorAll('[data-role="network-cert"]').length`)) === 1, 5000, 'the certificate row')
  const rowText = await evaluate(`document.querySelector('[data-role="network-cert"]').textContent.replace(/\\s+/g, ' ')`)
  check('lists the host and the file name', rowText.includes('127.0.0.1') && rowText.includes('client.p12'), rowText)
  check('the row shows no directory', !rowText.includes('fixtures') && !rowText.includes('/'), rowText)
  check('the row says a passphrase is saved without showing it', rowText.includes('passphrase saved') && !rowText.includes('clientpw'), rowText)

  const withCert = await mtlsSend()
  check('the same request succeeds with the certificate', withCert.ok && withCert.body === 'ping-client', JSON.stringify(withCert))

  const certView = JSON.stringify(await evaluate(`window.ping.network.get()`))
  check('the settings carry no path and no passphrase', !certView.includes('fixtures') && !certView.includes('clientpw'), certView)
  const certFile = readFileSync(join(userDataDir, 'network.json'), 'utf8')
  check('the passphrase is not stored in the clear', !certFile.includes('clientpw'), certFile.slice(0, 240))

  // A collection cannot bring its own: the renderer's `network` is discarded, so it cannot name a file.
  await evaluate(`document.querySelector('[data-role="network-cert-remove"]').click()`)
  await waitFor(async () => (await evaluate(`document.querySelectorAll('[data-role="network-cert"]').length`)) === 0, 5000, 'the row to go')
  const afterRemove = await mtlsSend()
  check('removing the certificate takes the access away', !afterRemove.ok, JSON.stringify(afterRemove))

  // PEM: the shell asks for the certificate, then the key.
  await evaluate(fillNetwork('network-cert-type', 'pem'))
  await evaluate(fillNetwork('network-cert-host', '127.0.0.1'))
  await evaluate(`document.querySelector('[data-role="network-cert-add"]').click()`)
  await waitFor(async () => (await evaluate(`document.querySelectorAll('[data-role="network-cert"]').length`)) === 1, 5000, 'the PEM row')
  const pemText = await evaluate(`document.querySelector('[data-role="network-cert"]').textContent.replace(/\\s+/g, ' ')`)
  check('a PEM certificate lists both files', pemText.includes('client.pem') && pemText.includes('client.pk8.pem'), pemText)
  const viaPem = await mtlsSend()
  check('a PEM certificate and key work too', viaPem.ok && viaPem.body === 'ping-client', JSON.stringify(viaPem))

  // Other hosts are not offered it: the same server by another name gets no identity to refuse.
  const otherHost = await evaluate(`window.ping.request('http.send', ${JSON.stringify({ url: `https://localhost:${MTLS_PORT}/who`, method: 'GET', verifyTls: false, timeoutMs: 8000 })})`)
  check('a certificate is not offered to a host it does not name', !otherHost.ok, JSON.stringify(otherHost).slice(0, 160))

  await evaluate(`document.querySelector('[data-role="network-cert-remove"]').click()`)
  await waitFor(async () => (await evaluate(`document.querySelectorAll('[data-role="network-cert"]').length`)) === 0, 5000, 'the PEM row to go')
  await evaluate(`document.querySelector('[data-role="network-cancel"]').click()`)

  console.log('--- 15k. connection probe')
  // The active tab has just been sent to the local server, so its response can be probed.
  await evaluate(clickTab('Timing'))
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="probe-run"]')`), 3000, 'the probe section')
  check('nothing is probed until asked', !(await evaluate(`!!document.querySelector('[data-role="probe-result"]')`)))
  const probeIntro = await evaluate(`document.querySelector('[data-role="probe"]').textContent.replace(/\\s+/g, ' ')`)
  check('says it is a separate connection, not part of Total', /separate connection/.test(probeIntro) && /not part of Total/.test(probeIntro), probeIntro.slice(0, 200))
  await evaluate(`document.querySelector('[data-role="probe-run"]').click()`)
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="probe-result"]')`), 8000, 'the probe result')
  const probeText = await evaluate(`document.querySelector('[data-role="probe-result"]').textContent.replace(/\\s+/g, ' ')`)
  check('shows DNS and TCP for a plain connection', /DNS/.test(probeText) && /TCP connect/.test(probeText) && !/TLS handshake/.test(probeText), probeText)
  check('a probe stage is timed to a tenth', /\d+\.\d ms/.test(probeText), probeText)

  const probeRpc = (extra) => evaluate(`window.ping.request('net.probe', ${JSON.stringify(extra)})`)
  const tlsProbe = await probeRpc({ url: `https://127.0.0.1:${MTLS_PORT}/secret?k=hunter2`, verifyTls: false })
  check('a TLS probe reports the handshake and the certificate', tlsProbe.ok && tlsProbe.value.tlsMs !== undefined && tlsProbe.value.certificate?.subject === 'CN=localhost' && /^TLSv1\./.test(tlsProbe.value.protocol), JSON.stringify(tlsProbe).slice(0, 200))
  check('the probe never echoes the request path or query', !JSON.stringify(tlsProbe).includes('hunter2') && !JSON.stringify(tlsProbe).includes('/secret'))
  const untrusted = await probeRpc({ url: `https://127.0.0.1:${MTLS_PORT}/`, verifyTls: true })
  check('an untrusted certificate stops at TLS but is still shown', untrusted.ok && untrusted.value.failedStage === 'tls' && untrusted.value.certificate?.subject === 'CN=localhost', JSON.stringify(untrusted).slice(0, 200))
  const dead = await probeRpc({ url: 'http://127.0.0.1:1/' })
  check('a closed port stops at connect', dead.ok && dead.value.failedStage === 'connect', JSON.stringify(dead).slice(0, 200))
  const sneakedProbe = await probeRpc({ url: `${base}/`, network: { proxy: { mode: 'manual', url: '127.0.0.1:1' } } })
  check('ignores a proxy the renderer invents', sneakedProbe.ok && sneakedProbe.value.viaProxy === false && !sneakedProbe.value.failedStage, JSON.stringify(sneakedProbe).slice(0, 200))
  const bad = await probeRpc({ url: 'ftp://example.com/' })
  check('refuses an address that is not http(s)', bad.ok === false, JSON.stringify(bad).slice(0, 160))

  // The per-request HTTP version is saved with the request.
  await evaluate(clickTab('Settings'))
  await evaluate(`(() => {
    const select = document.getElementById('setting-http-version');
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(select, '1.1');
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  await evaluate(`document.querySelector('[data-role="save"]')?.click()`)
  await waitFor(async () => /httpVersion: ['"]?1\.1/.test(readFileSync(join(workspaceDir, 'demo', 'new-request.yaml'), 'utf8')), 5000, 'the version on disk')
  check('the pinned HTTP version is saved in the request file', true)
  await clickSend()
  await waitFor(async () => (await snap()).status === '200', 5000, 'a send with a pinned version')
  check('a send with a pinned version succeeds', true)

  console.log('--- 15l. streaming responses')
  // The SSE parser is plain TypeScript with no DOM: bundle it and test it directly.
  const sseOut = join(userDataDir, 'sse.mjs')
  buildSync({
    entryPoints: [fileURLToPath(new URL('../src/renderer/src/lib/sse.ts', import.meta.url))],
    outfile: sseOut, format: 'esm', bundle: true, logLevel: 'silent'
  })
  const { SseParser, parseSse } = await import(pathToFileURL(sseOut).href)
  const feed = (chunks) => { const p = new SseParser(); return chunks.flatMap((c, i) => p.push(c, i * 10)) }
  check('parses one event', JSON.stringify(feed(['event: tick\ndata: hi\nid: 7\n\n']).map(e => [e.event, e.data, e.id])) === '[["tick","hi","7"]]')
  check('joins multi-line data', feed(['data: a\ndata: b\n\n'])[0].data === 'a\nb')
  check('waits for the rest of an event split across chunks', JSON.stringify(feed(['data: he', 'llo\n', '\n']).map(e => e.data)) === '["hello"]')
  check('a CRLF split between chunks is one line ending', JSON.stringify(feed(['data: x\r', '\n\r', '\n']).map(e => e.data)) === '["x"]')
  check('bare CR and CRLF both end lines', feed(['data: a\r\rdata: b\r\n\r\n']).length === 2)
  check('ignores comments and drops a record with no data', feed([': hi\n\nevent: only\n\ndata: y\n\n']).length === 1)
  check('a colon with no space and a bare field name work', JSON.stringify(feed(['data:z\ndata\n\n']).map(e => e.data)) === '["z\\n"]')
  check('id carries to later events and retry is numeric', (() => { const e = feed(['id: 5\ndata: 1\n\ndata: 2\nretry: 30\n\n']); return e[0].id === '5' && e[1].id === '5' && e[1].retry === 30 && e[0].retry === undefined })())
  check('an unfinished trailing event is not returned', feed(['data: a\n\ndata: b']).length === 1)
  check('numbers events and stamps their chunk time', (() => { const e = feed(['data: a\n\n', 'data: b\n\n']); return e[1].index === 2 && e[1].atMs === 10 })())
  check('parseSse finishes an unterminated body', parseSse('data: a\n\ndata: b').length === 2)

  await evaluate(clickResponseTab('Body'))
  await evaluate(setUrl(`${base}/events`))
  await clickSend()
  await waitFor(async () => (await evaluate(`document.querySelectorAll('[data-role="sse-event"]').length`)) >= 1, 8000, 'the first event')
  const submitText = await evaluate(`document.querySelector('button[type=submit]').textContent.trim()`)
  check('the first event shows while the request is still in flight', submitText === 'Streaming…', submitText)
  const stopShown = await evaluate(`[...document.querySelectorAll('button')].some(b => b.textContent.trim() === 'Stop')`)
  check('offers Stop rather than Cancel', stopShown)
  const liveNote = await evaluate(`document.querySelector('[data-role="stream-note"]')?.textContent.trim()`)
  check('says it is streaming', /^Streaming · 1 event/.test(liveNote ?? ''), liveNote)
  const eventText = await evaluate(`document.querySelector('[data-role="sse-event"]').textContent.replace(/\\s+/g, ' ')`)
  check('shows the event name and pretty-prints JSON data', eventText.includes('tick') && /"n":\s*1/.test(eventText), eventText)
  check('the second event has not arrived yet', (await evaluate(`document.querySelectorAll('[data-role="sse-event"]').length`)) === 1)

  await fetch(`${base}/events/release`)
  await waitFor(async () => (await evaluate(`document.querySelectorAll('[data-role="sse-event"]').length`)) === 2, 8000, 'the second event')
  check('the next event appears as it arrives', true)

  await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Stop').click()`)
  await waitFor(async () => (await evaluate(`document.querySelector('button[type=submit]').textContent.trim()`)) === 'Send', 8000, 'the stop to finish')
  check('Stop ends the exchange', true)
  check('the events stay on screen', (await evaluate(`document.querySelectorAll('[data-role="sse-event"]').length`)) === 2)
  const stoppedNote = await evaluate(`document.querySelector('[data-role="stream-note"]')?.textContent.trim()`)
  check('says it was stopped, not failed', /^Stopped · 2 events/.test(stoppedNote ?? ''), stoppedNote)
  const streamError = await evaluate(`!!document.querySelector('[role=alert]')`)
  check('a stop is not reported as an error', !streamError)
  await waitFor(async () => feedClosed, 5000, 'the server to see the disconnect')
  check('the server sees the connection released', feedClosed)

  console.log('--- 15m. run a whole collection')
  // The run happens in the core; what is proved here is the shell's half of it: the button on a
  // collection, progress arriving as notifications, the environment picked for the run rather
  // than taken from the header, and a failed assertion shown for the request it belongs to.
  const runRows = () =>
    evaluate(`[...document.querySelectorAll('[data-role="run-request"]')].map(r => r.dataset.outcome)`)
  const runSummary = () =>
    evaluate(`document.querySelector('[data-role="run-summary"]')?.textContent.replace(/\\s+/g, ' ').trim() ?? null`)

  await evaluate(`document.querySelector('button[aria-label="Run run demo"]').click()`)
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="run-dialog"]')`), 5000, 'the run dialog')
  check('a collection offers a run', true)
  const runEnvs = await evaluate(
    `[...document.querySelectorAll('select[aria-label="Environment for the run"] option')].map(o => o.textContent.trim())`
  )
  check("offers the run collection's own environments", runEnvs.join() === 'No environment,Dev', runEnvs.join())
  check(
    'does not take the environment from the header',
    (await evaluate(`document.querySelector('select[aria-label="Environment for the run"]').value`)) === ''
  )

  // With no environment the collection's own host points nowhere: every request errors, and the
  // run reports that rather than stopping at the first one.
  await evaluate(`document.querySelector('[data-role="run-start"]').click()`)
  await waitFor(async () => (await runSummary()) !== null, 20_000, 'the run to finish')
  check('a request with no response is errored, and the run carries on', (await runRows()).join() === 'errored,errored', (await runRows()).join())
  const deadRun = await runSummary()
  check('summarises the run', /0\/2 passed, 2 errored/.test(deadRun ?? ''), deadRun)

  // The same collection against the loopback server: one assertion holds, one does not.
  await evaluate(setSelect('Environment for the run', 'run-demo/environments/dev.yaml'))
  await evaluate(`document.querySelector('[data-role="run-start"]').click()`)
  await waitFor(async () => /1\/2 passed/.test((await runSummary()) ?? ''), 20_000, 'the second run')
  const liveRows = await runRows()
  check('shows each request as it finishes', liveRows.length === 2, liveRows.join())
  check('a failed assertion is not an error', liveRows.join() === 'passed,failed', liveRows.join())
  const liveRun = await runSummary()
  check('names the environment the run used', /1\/2 passed, 1 failed/.test(liveRun ?? '') && /Dev/.test(liveRun ?? ''), liveRun)
  const failedRow = await evaluate(
    `document.querySelector('[data-role="run-request"][data-outcome="failed"]')?.textContent.replace(/\\s+/g, ' ').trim() ?? ''`
  )
  check('says which assertion failed', /Two red/.test(failedRow) && /status equals 500/.test(failedRow), failedRow)

  await evaluate(`document.querySelector('[data-role="run-close"]').click()`)
  await waitFor(async () => !(await evaluate(`!!document.querySelector('[data-role="run-dialog"]')`)), 5000, 'the run dialog to close')
  check('the run panel closes', true)

  console.log('--- 16. in-app update flow')
  await evaluate(pressCtrlK)
  await waitFor(
    async () => await evaluate(`!!document.querySelector('[data-role="palette"]')`),
    2000,
    'the command palette'
  )
  await evaluate(typeInPalette('updates'))
  const updateOption =
    `[...document.querySelectorAll('[data-role="palette"] [role="option"]')]` +
    `.find(o => o.textContent.includes('Check for updates'))`
  await waitFor(async () => await evaluate(`!!${updateOption}`), 2000, 'the update command')
  check('offers a check-for-updates command', true)
  await evaluate(`${updateOption}?.click()`)

  await waitFor(
    async () =>
      await evaluate(
        `document.querySelector('[data-role="update-banner"]')?.dataset.status === 'available'`
      ),
    3000,
    'the update offer'
  )
  const offer = await evaluate(`document.querySelector('[data-role="update-banner"]').textContent`)
  check('shows the offered version', offer.includes('99.0.0'), offer.trim())

  await evaluate(`document.querySelector('[data-role="update-download"]').click()`)
  await waitFor(
    async () =>
      await evaluate(
        `document.querySelector('[data-role="update-banner"]')?.dataset.status === 'downloading'`
      ),
    2000,
    'the download to start'
  )
  check('downloads only after the user asks', true)
  await waitFor(
    async () =>
      await evaluate(
        `document.querySelector('[data-role="update-banner"]')?.dataset.status === 'downloaded'`
      ),
    5000,
    'the download to finish'
  )
  check('reports the update ready to install', true)

  await evaluate(`document.querySelector('[data-role="update-install"]').click()`)
  // Unsaved work gets an in-app confirmation before the restart.
  await acceptPrompt()
  await waitFor(
    async () =>
      await evaluate(
        `document.querySelector('[data-role="update-banner"]')?.dataset.status === 'installing'`
      ),
    3000,
    'the install to start'
  )
  check('installs only after the user asks', true)
} catch (cause) {
  failures++
  console.error(`FAIL: ${cause instanceof Error ? cause.message : String(cause)}`)
} finally {
  socket?.close()
  await stopApp()
  server.closeAllConnections?.()
  server.close()
  proxy.closeAllConnections?.()
  proxy.close()
  mtls.closeAllConnections?.()
  mtls.close()
  rmSync(workspaceDir, { recursive: true, force: true })
  rmSync(userDataDir, { recursive: true, force: true })
}

console.log(failures === 0 ? '\nAll smoke checks passed.' : `\n${failures} smoke check(s) failed.`)
process.exit(failures === 0 ? 0 : 1)

/** Signals the whole process group; SIGKILL after a grace period so nothing survives. */
function signalGroup(signal) {
  if (process.platform === 'win32') {
    app.kill(signal)
    return
  }
  try {
    process.kill(-app.pid, signal)
  } catch {
    // The group is already gone.
  }
}

/** Terminates the app's process group, escalating to SIGKILL if it does not exit promptly. */
async function stopApp() {
  if (!app.pid || app.exitCode !== null || app.signalCode !== null) {
    return
  }
  const exited = new Promise((resolve) => app.once('exit', resolve))
  signalGroup('SIGTERM')
  const force = setTimeout(() => signalGroup('SIGKILL'), 3000)
  await exited
  clearTimeout(force)
}

async function findPage() {
  for (let attempt = 0; attempt < 60; attempt++) {
    await wait(500)
    const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json`).catch(() => null)
    if (!response) continue
    const targets = await response.json()
    const page = targets.find((target) => target.type === 'page' && target.title === 'Ping')
    if (page) return page
  }
  return null
}
