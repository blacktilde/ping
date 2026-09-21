/**
 * README screenshot generator.
 *
 * Launches the built app against a throwaway workspace and a temporary profile, drives it
 * over the DevTools Protocol, and writes PNGs to docs/screenshots. The workspace is a
 * realistic collection pointed at JSONPlaceholder, so the responses in the shots are real.
 *
 * Run `npm run build` first, then `node test/screenshots.mjs`.
 */
import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import electron from 'electron'

const DEBUG_PORT = 9225
const WIDTH = 1440
const HEIGHT = 900
const SCALE = 2
const outDir = process.argv[2] ?? join(new URL('..', import.meta.url).pathname, '..', 'docs', 'screenshots')
const root = new URL('..', import.meta.url)

// --- throwaway workspace ------------------------------------------------------------------

const workspaceDir = mkdtempSync(join(tmpdir(), 'ping-shots-'))
const collection = join(workspaceDir, 'JSONPlaceholder')
mkdirSync(join(collection, 'environments'), { recursive: true })
mkdirSync(join(collection, 'users'), { recursive: true })
mkdirSync(join(collection, 'posts'), { recursive: true })

writeFileSync(
  join(collection, 'collection.yaml'),
  ['name: JSONPlaceholder', 'variables:', '  - name: baseUrl', '    value: https://jsonplaceholder.typicode.com', ''].join('\n')
)
writeFileSync(
  join(collection, 'environments', 'production.yaml'),
  ['name: Production', 'variables:', '  - name: baseUrl', '    value: https://jsonplaceholder.typicode.com', ''].join('\n')
)
writeFileSync(
  join(collection, 'environments', 'local.yaml'),
  ['name: Local', 'variables:', '  - name: baseUrl', '    value: http://localhost:3000', ''].join('\n')
)

const usersDir = join(collection, 'users')
writeFileSync(
  join(usersDir, 'list-users.yaml'),
  ['name: List users', 'method: GET', "url: '{{baseUrl}}/users'", 'query:', '  - name: _limit', "    value: '3'", ''].join('\n')
)
writeFileSync(
  join(usersDir, 'get-user.yaml'),
  ['name: Get user', 'method: GET', "url: '{{baseUrl}}/users/1'", ''].join('\n')
)

const postsDir = join(collection, 'posts')
writeFileSync(
  join(postsDir, 'list-posts.yaml'),
  ['name: List posts', 'method: GET', "url: '{{baseUrl}}/posts'", 'query:', '  - name: userId', "    value: '1'", ''].join('\n')
)
writeFileSync(
  join(postsDir, 'create-post.yaml'),
  [
    'name: Create post',
    'method: POST',
    "url: '{{baseUrl}}/posts'",
    'headers:',
    '  - name: Content-Type',
    '    value: application/json',
    'body:',
    '  type: json',
    '  content: |-',
    '    {',
    '      "title": "Ping",',
    '      "body": "Sent from the desktop app",',
    '      "userId": 1',
    '    }',
    ''
  ].join('\n')
)

const userDataDir = mkdtempSync(join(tmpdir(), 'ping-shots-userdata-'))

// --- app over CDP -------------------------------------------------------------------------

const app = spawn(
  electron,
  [
    '.',
    `--remote-debugging-port=${DEBUG_PORT}`,
    '--no-sandbox',
    `--user-data-dir=${userDataDir}`
  ],
  {
    cwd: root,
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
    env: { ...process.env, PING_WORKSPACE: workspaceDir }
  }
)
app.stderr.on('data', (chunk) => {
  const text = String(chunk)
  if (/\[core\]|\[renderer\]/.test(text)) process.stderr.write(text)
})

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

let socket
try {
  console.error('[shots] waiting for the renderer')
  const page = await findPage()
  if (!page) throw new Error('renderer never appeared')
  console.error('[shots] connecting to', page.webSocketDebuggerUrl)

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
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id)
        reject(new Error(`CDP ${method} timed out`))
      }, 15000)
      pending.set(id, (message) => {
        clearTimeout(timer)
        resolve(message)
      })
    })
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
  await cdp('Page.enable')
  await cdp('Emulation.setDeviceMetricsOverride', {
    width: WIDTH,
    height: HEIGHT,
    deviceScaleFactor: SCALE,
    mobile: false
  })
  // Force the dark theme and the split sizes before the app boots, so shots are consistent
  // regardless of the host's color-scheme and stored layout.
  await cdp('Page.addScriptToEvaluateOnNewDocument', {
    source: [
      "localStorage.setItem('ping.theme','dark')",
      "localStorage.setItem('ping.split.sidebar','280')",
      "localStorage.setItem('ping.split.request','0.38')"
    ].join(';')
  })
  await cdp('Emulation.setEmulatedMedia', {
    features: [{ name: 'prefers-color-scheme', value: 'dark' }]
  })
  console.error('[shots] connected; loading workspace view')
  await cdp('Page.reload')

  async function waitFor(predicate, timeoutMs, label) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (await predicate()) return
      await wait(200)
    }
    throw new Error(`timed out waiting for ${label}`)
  }

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

  const clickNode = (name) => `(() => {
    const button = [...document.querySelectorAll('[data-node-type="request"] button')]
      .find(b => b.textContent.includes(${JSON.stringify(name)}));
    if (!button) return false;
    button.click();
    return true;
  })()`

  const clickRequestTab = (label) => `(() => {
    const root = document.querySelector('[data-role="request"]');
    const tab = root && [...root.querySelectorAll('[role=tab]')].find(t => t.textContent.trim().startsWith(${JSON.stringify(label)}));
    if (!tab) return false;
    tab.click();
    return true;
  })()`

  const clickResponseTab = (label) => `(() => {
    const root = document.querySelector('[data-role="response"]');
    const tab = root && [...root.querySelectorAll('[role=tab]')].find(t => t.textContent.trim().startsWith(${JSON.stringify(label)}));
    if (!tab) return false;
    tab.click();
    return true;
  })()`

  const urlValue = () => `document.querySelector('input[aria-label="Request URL"]')?.value ?? ''`
  const status = () => `document.querySelector('[data-role="response"] header span')?.textContent.trim() ?? null`
  const clickSend = () => evaluate(`document.querySelector('button[type=submit]')?.click()`)

  async function openRequest(name, url) {
    await evaluate(clickNode(name))
    await waitFor(async () => (await evaluate(urlValue())) === url, 6000, `${name} to open`)
  }

  async function send() {
    await clickSend()
    await waitFor(async () => (await evaluate(status())) !== null, 20000, 'a response')
  }

  async function capture(name) {
    await wait(350)
    const response = await cdp('Page.captureScreenshot', { format: 'png', fromSurface: true })
    const data = response.result?.data
    if (!data) throw new Error(`screenshot ${name} returned nothing`)
    mkdirSync(outDir, { recursive: true })
    writeFileSync(join(outDir, name), Buffer.from(data, 'base64'))
    console.log(`wrote ${name}`)
  }

  await waitFor(async () => (await evaluate(urlValue())).length > 0, 12000, 'the app to boot')
  const sidebar = `document.querySelector('[data-role="sidebar"]')?.textContent ?? ''`
  await waitFor(async () => (await evaluate(sidebar)).includes('List users'), 12000, 'the tree to load')

  // Pin the environment so the URL bar resolves against the real API.
  await evaluate(setSelect('Environment', 'JSONPlaceholder/environments/production.yaml'))
  await wait(600)

  console.log('--- hero: a real request and response')
  await openRequest('List users', '{{baseUrl}}/users')
  await evaluate(clickRequestTab('Params'))
  await send()
  await waitFor(async () => (await evaluate(status())) === '200', 20000, 'a 200')
  await evaluate(clickResponseTab('Body'))
  await capture('hero.png')

  console.log('--- request builder: JSON body')
  await openRequest('Create post', '{{baseUrl}}/posts')
  await evaluate(clickRequestTab('Body'))
  await waitFor(
    async () => await evaluate(`!!document.querySelector('[data-role="request"] .cm-content')`),
    4000,
    'the body editor'
  )
  await send()
  await waitFor(async () => (await evaluate(status())) === '201', 20000, 'a 201')
  await evaluate(clickResponseTab('Body'))
  await capture('request-builder.png')

  console.log('--- history')
  await openRequest('Get user', '{{baseUrl}}/users/1')
  await send()
  await openRequest('List posts', '{{baseUrl}}/posts')
  await send()
  await evaluate(clickText('History'))
  await waitFor(
    async () => (await evaluate(`document.querySelectorAll('[data-role="history-entry"]').length`)) >= 4,
    4000,
    'history entries'
  )
  await capture('history.png')
  await evaluate(clickText('Collections'))

  console.log('--- variables and secrets')
  await evaluate(clickText('Variables'))
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-role="variables"]')`), 3000, 'the panel')
  await evaluate(clickText('+ Add secret'))
  await evaluate(setInput('Secret name', 'apiToken'))
  await evaluate(setInput('Secret value', 'sk_live_8f2c1a'))
  await evaluate(clickText('Save variables'))
  // The button says "Saved!" for a moment after a save; wait it out so the shot shows the label.
  await waitFor(
    async () =>
      (await evaluate(`document.querySelector('[data-role="save-variables"]')?.textContent.trim()`)) ===
      'Save variables',
    5000,
    'the save confirmation to fade'
  )
  await wait(300)
  await capture('variables.png')
} catch (cause) {
  console.error(`FAIL: ${cause instanceof Error ? cause.message : String(cause)}`)
  process.exitCode = 1
} finally {
  socket?.close()
  await stopApp()
  rmSync(workspaceDir, { recursive: true, force: true })
  rmSync(userDataDir, { recursive: true, force: true })
}

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
