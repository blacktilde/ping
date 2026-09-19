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

// Echoes what reached the server so the test can prove the editors are wired to the wire.
const server = http.createServer(async (req, res) => {
  if (req.url.startsWith('/slow')) {
    setTimeout(() => {
      res.writeHead(200, { 'Content-Type': 'text/plain' })
      res.end('late')
    }, 30_000)
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
mkdirSync(join(workspaceDir, 'demo'), { recursive: true })
const savedRequest = join(workspaceDir, 'demo', 'get.yaml')
writeFileSync(savedRequest, `name: Smoke get\nmethod: GET\nurl: ${base}/data\n`)

const app = spawn(
  electron,
  ['.', `--remote-debugging-port=${DEBUG_PORT}`, '--disable-gpu', '--no-sandbox'],
  {
    cwd: root,
    stdio: ['ignore', 'pipe', 'pipe'],
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
    console.error('FAIL: renderer never appeared')
    process.exit(1)
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
    const input = document.querySelector('input[aria-label="${label}"]');
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
      `${base}/data`
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
  check('sends the query parameter', withRows?.url === '/data?smoke=1', withRows?.url ?? 'none')
  check('sends the header', withRows?.headers?.['x-smoke'] === 'yes', withRows?.headers?.['x-smoke'] ?? 'none')

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
  check('renders HTML in a sandboxed frame', (await snap()).body.includes('preview me'), 'preview')

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
  await evaluate(setUrl(`${base}/slow`))
  await clickSend()
  await waitFor(async () => (await snap()).cancelVisible, 3000, 'the cancel button')
  const during = await snap()
  check('reports the request in flight', during.sendLabel === 'Sending…', during.sendLabel ?? 'none')
  check('offers cancellation', during.cancelVisible)
  await evaluate(
    `[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Cancel').click()`
  )
  await waitFor(async () => (await snap()).cancelled !== null, 3000, 'the cancelled state')
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
} catch (cause) {
  failures++
  console.error(`FAIL: ${cause instanceof Error ? cause.message : String(cause)}`)
} finally {
  socket?.close()
  app.kill()
  server.close()
  rmSync(workspaceDir, { recursive: true, force: true })
}

console.log(failures === 0 ? '\nAll smoke checks passed.' : `\n${failures} smoke check(s) failed.`)
process.exit(failures === 0 ? 0 : 1)

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
