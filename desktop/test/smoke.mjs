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
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
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
const server = http.createServer(async (req, res) => {
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

  const chunks = []
  for await (const chunk of req) chunks.push(chunk)

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
    env: { ...process.env, PING_WORKSPACE: workspaceDir }
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
  const clickSend = () => evaluate(`document.querySelector('button[type=submit]').click()`)

  async function waitFor(predicate, timeoutMs, label) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (await predicate()) return
      await wait(200)
    }
    throw new Error(`timed out waiting for ${label}`)
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
      (await evaluate(`document.querySelector('input[aria-label="Request name"]')?.value`)) ===
      'Smoke get',
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
  await wait(400)
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

  await evaluate(pressCtrlK)
  await wait(200)
  await evaluate(`${themeOption}?.click()`)
  await waitFor(
    async () => (await evaluate(`document.documentElement.dataset.theme`)) === initialTheme,
    2000,
    'the theme to return'
  )
  check('theme command switches back', true)

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
} catch (cause) {
  failures++
  console.error(`FAIL: ${cause instanceof Error ? cause.message : String(cause)}`)
} finally {
  socket?.close()
  await stopApp()
  server.closeAllConnections?.()
  server.close()
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
