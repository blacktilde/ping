/**
 * The shell's diagnostic log: what it masks before a line is kept, how it bounds itself, and the
 * file it writes. Whatever passes here is shown in the renderer and saved to disk.
 */
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { Logger, LogLines, redact } from '../src/main/log.ts'

function quietLogger(options: { capacity?: number; maxFileBytes?: number } = {}): {
  logger: Logger
  echoed: string[]
} {
  const echoed: string[] = []
  return { logger: new Logger({ ...options, echo: (line) => echoed.push(line) }), echoed }
}

test('a known secret value is masked wherever it appears', () => {
  assert.equal(
    redact('GET https://api.test/items?q=s3cr3t-token failed: s3cr3t-token', ['s3cr3t-token']),
    'GET https://api.test/items?q=*** failed: ***'
  )
})

test('a longer secret is masked whole even when a shorter one is inside it', () => {
  assert.equal(redact('value=abcd-efgh', ['abcd', 'abcd-efgh']), 'value=***')
})

test('a very short value is not masked, so it cannot blank out the log', () => {
  assert.equal(redact('status 200 ok', ['0']), 'status 200 ok')
})

test('credential headers are masked in plain and JSON form', () => {
  assert.equal(redact('Authorization: Bearer abc.def'), 'Authorization: ***')
  assert.equal(redact('proxy-authorization=Basic Zm9v'), 'proxy-authorization=***')
  assert.equal(redact('Set-Cookie: session=1; HttpOnly'), 'Set-Cookie: ***')
  assert.equal(
    redact('{"Authorization":"Bearer abc","Accept":"*/*","cookie":"a=\\"b\\""}'),
    '{"Authorization":"***","Accept":"*/*","cookie":"***"}'
  )
})

test('prose that merely names a header is left alone', () => {
  assert.equal(redact('Authorization failed for user'), 'Authorization failed for user')
})

test("a URL's password and credential-like query parameters are masked", () => {
  assert.equal(
    redact('connect http://user:hunter2@proxy.test:8080/ failed'),
    'connect http://user:***@proxy.test:8080/ failed'
  )
  assert.equal(
    redact('GET https://a.test/cb?code=1&access_token=xyz&state=2'),
    'GET https://a.test/cb?code=1&access_token=***&state=2'
  )
  assert.equal(redact('GET https://a.test/?api_key=k1#frag'), 'GET https://a.test/?api_key=***#frag')
})

test('lines are masked before they are kept, echoed and handed to listeners', () => {
  const { logger, echoed } = quietLogger()
  logger.maskValues(() => ['p4ssw0rd'])
  const seen: string[] = []
  logger.onEntry((entry) => seen.push(entry.text))
  logger.write('network', 'proxy refused p4ssw0rd')
  assert.equal(logger.list()[0].text, 'proxy refused ***')
  assert.equal(logger.list()[0].source, 'network')
  assert.deepEqual(echoed, ['[network] proxy refused ***\n'])
  assert.deepEqual(seen, ['proxy refused ***'])
})

test('a mask provider that throws does not lose the line', () => {
  const { logger } = quietLogger()
  logger.maskValues(() => {
    throw new Error('store not ready')
  })
  logger.write('core', 'Authorization: Bearer x')
  assert.equal(logger.list()[0].text, 'Authorization: ***')
})

test('a multi-line message becomes one entry per line, blank lines dropped', () => {
  const { logger } = quietLogger()
  logger.write('core', 'java.lang.IllegalStateException: boom\n\tat A.b(A.java:1)\n\n')
  assert.deepEqual(
    logger.list().map((entry) => entry.text),
    ['java.lang.IllegalStateException: boom', '\tat A.b(A.java:1)']
  )
})

test('the buffer keeps only the newest lines, with increasing sequence numbers', () => {
  const { logger } = quietLogger({ capacity: 3 })
  for (let i = 1; i <= 5; i++) {
    logger.write('t', `line ${i}`)
  }
  const kept = logger.list()
  assert.deepEqual(kept.map((entry) => entry.text), ['line 3', 'line 4', 'line 5'])
  assert.deepEqual(kept.map((entry) => entry.seq), [3, 4, 5])
  logger.clear()
  assert.deepEqual(logger.list(), [])
  logger.write('t', 'after')
  assert.equal(logger.list()[0].seq, 6)
})

test('a huge line is cut', () => {
  const { logger } = quietLogger()
  logger.write('core', `unparseable line: ${'x'.repeat(50_000)}`)
  const text = logger.list()[0].text
  assert.ok(text.length < 4100, `length ${text.length}`)
  assert.match(text, /more characters\)$/)
})

test('the file starts with what was logged before it was attached, and rolls over', () => {
  const dir = mkdtempSync(join(tmpdir(), 'ping-log-'))
  try {
    const { logger } = quietLogger({ maxFileBytes: 200 })
    logger.write('core', 'before attach')
    logger.attach(join(dir, 'logs'))
    assert.equal(logger.dir(), join(dir, 'logs'))
    const first = readFileSync(join(dir, 'logs', 'ping.log'), 'utf8')
    assert.match(first, /^\d{4}-\d\d-\d\dT[\d:.]+Z \[core\] before attach\n$/)

    for (let i = 0; i < 5; i++) {
      logger.write('core', `line ${i} ${'y'.repeat(40)}`)
    }
    const current = readFileSync(join(dir, 'logs', 'ping.log'), 'utf8')
    const previous = readFileSync(join(dir, 'logs', 'ping.1.log'), 'utf8')
    assert.ok(Buffer.byteLength(current) <= 200)
    assert.ok(previous.length > 0)
    assert.match(current, /line 4/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('a folder that cannot be written is reported and the log keeps working in memory', () => {
  const dir = mkdtempSync(join(tmpdir(), 'ping-log-'))
  try {
    const blocker = join(dir, 'not-a-folder')
    writeFileSync(blocker, '')
    const { logger, echoed } = quietLogger()
    logger.attach(blocker)
    assert.equal(logger.dir(), null)
    assert.match(echoed[0], /^\[log\] cannot write logs/)
    logger.write('core', 'still kept')
    assert.equal(logger.list()[0].text, 'still kept')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('stream chunks are reassembled into lines that keep their indentation', () => {
  const lines = new LogLines()
  assert.deepEqual(lines.push('Exception: bo'), [])
  assert.deepEqual(lines.push('om\r\n\tat A.b\n'), ['Exception: boom', '\tat A.b'])
  assert.deepEqual(lines.push('\n  \ntail'), [])
  assert.deepEqual(lines.flush(), ['tail'])
  assert.deepEqual(lines.flush(), [])
})
