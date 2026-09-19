/**
 * UI smoke test.
 *
 * Drives the built app over the Chrome DevTools Protocol and reads the DOM back, because
 * synthetic keyboard input does not reach Electron on every setup. It covers the paths
 * that are easy to get wrong at the IPC boundary: a normal exchange, a cancellation that
 * must not look like a failure, and an unreachable host that must not leak Java class
 * names or Electron's internal IPC prefix.
 *
 * Run `npm run build` first, then `npm run smoke`.
 */
import { spawn } from 'node:child_process'
import http from 'node:http'
import electron from 'electron'

const PORT = 8791
const DEBUG_PORT = 9225
const root = new URL('..', import.meta.url)

const server = http.createServer((req, res) => {
  if (req.url.startsWith('/slow')) {
    setTimeout(() => {
      res.writeHead(200, { 'Content-Type': 'text/plain' })
      res.end('late')
    }, 30_000)
    return
  }
  res.writeHead(200, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ ok: true, from: 'local test server' }))
})
await new Promise((resolve) => server.listen(PORT, '127.0.0.1', resolve))

const app = spawn(
  electron,
  ['.', `--remote-debugging-port=${DEBUG_PORT}`, '--disable-gpu', '--no-sandbox'],
  { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] }
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

  const setUrl = (value) => `(() => {
    const input = document.querySelector('input');
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, ${JSON.stringify(value)});
    input.dispatchEvent(new Event('input', { bubbles: true }));
    return input.value;
  })()`

  const snapshot = `(() => {
    const section = document.querySelector('section');
    const error = document.querySelector('[data-role="error"]');
    const cancelled = document.querySelector('[data-role="cancelled"]');
    const submit = document.querySelector('button[type=submit]');
    return {
      status: section?.querySelector('header span')?.textContent.trim() ?? null,
      body: (section?.querySelector('pre')?.textContent ?? '').slice(0, 200),
      error: error ? error.textContent.trim() : null,
      cancelled: cancelled ? cancelled.textContent.trim() : null,
      sendLabel: submit ? submit.textContent.trim() : null,
      cancelVisible: [...document.querySelectorAll('button')].some(b => b.textContent.trim() === 'Cancel'),
    };
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

  console.log('--- 1. exchange with the local server')
  await evaluate(setUrl(`http://127.0.0.1:${PORT}/data`))
  await clickSend()
  await waitFor(async () => (await snap()).status === '200', 5000, 'a 200')
  const local = await snap()
  check('renders the status', local.status === '200', local.status ?? 'none')
  check('renders the body', local.body.includes('local test server'), local.body)
  check('shows no error', local.error === null)

  console.log('--- 2. exchange over real HTTPS')
  await evaluate(setUrl('https://jsonplaceholder.typicode.com/todos/1'))
  await clickSend()
  await waitFor(
    async () => (await snap()).body.includes('delectus aut autem'),
    8000,
    'the HTTPS body'
  )
  const secure = await snap()
  check('renders the HTTPS status', secure.status === '200', secure.status ?? 'none')

  console.log('--- 3. cancel an in-flight request')
  await waitFor(async () => (await snap()).sendLabel === 'Send', 3000, 'the idle send button')
  await evaluate(setUrl(`http://127.0.0.1:${PORT}/slow`))
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

  console.log('--- 4. unreachable host')
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
