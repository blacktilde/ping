/** How a failed exchange is classified and laid out; the messages are the ones HttpEngine.describe writes. */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { classify, describeError, originOf, stageRows } from '../src/renderer/src/lib/errors.ts'

test('the core’s messages are told apart', () => {
  assert.equal(classify('Unknown host: nope.invalid'), 'dns')
  assert.equal(classify('Connection refused'), 'refused')
  assert.equal(classify('Connection refused: localhost/127.0.0.1:8080'), 'refused')
  assert.equal(classify('Request timed out'), 'timeout')
  assert.equal(
    classify('TLS handshake failed: PKIX path building failed: unable to find valid certification path'),
    'tls-untrusted'
  )
  assert.equal(classify('TLS handshake failed: Received fatal alert: bad_certificate'), 'tls-client-cert')
  assert.equal(classify('TLS handshake failed'), 'tls')
  assert.equal(classify('The server closed the connection without answering (if it requires a client)'), 'closed')
  assert.equal(classify('Malformed url: bad'), 'url')
  assert.equal(classify('something else'), 'other')
})

test('an unclassified message is shown as the core wrote it', () => {
  const info = describeError('odd failure', 'https://api.test/x')
  assert.equal(info.summary, 'odd failure')
  assert.equal(info.stage, undefined)
})

test('the title names the host, and falls back when the url holds a variable', () => {
  assert.match(describeError('Connection refused', 'http://localhost:8080/a').title, /localhost:8080/)
  assert.match(describeError('Connection refused', '{{baseUrl}}/a').title, /the server/)
})

test('an untrusted certificate offers both fixes', () => {
  const info = describeError('TLS handshake failed: PKIX path building failed', 'https://staging.test')
  assert.deepEqual(info.remedies, ['network-settings', 'skip-tls'])
})

test('stages before the failure are reached, after it idle', () => {
  const info = describeError('TLS handshake failed: PKIX path building failed', 'https://staging.test')
  assert.deepEqual(
    stageRows(info, 'https://staging.test').map((row) => [row.key, row.state]),
    [['dns', 'ok'], ['connect', 'ok'], ['tls', 'fail'], ['request', 'idle'], ['response', 'idle']]
  )
})

test('plain http has no TLS stage', () => {
  const info = describeError('Connection refused', 'http://localhost:8080')
  assert.deepEqual(stageRows(info, 'http://localhost:8080').map((row) => row.key), ['dns', 'connect', 'request', 'response'])
})

test('a timeout has no stage to point at, so no strip', () => {
  assert.deepEqual(stageRows(describeError('Request timed out', 'https://a.test'), 'https://a.test'), [])
})

test('a connection check supplies timings and can move the failure', () => {
  const info = describeError('Request timed out', 'https://a.test')
  const rows = stageRows(info, 'https://a.test', {
    origin: 'https://a.test', connectedTo: 'a.test', viaProxy: false, dnsMs: 4, connectMs: 18, failedStage: 'tls'
  })
  assert.equal(rows.find((row) => row.state === 'fail')?.key, 'tls')
  assert.equal(rows[0].ms, 4)
})

test('only variable-free http(s) urls can be probed', () => {
  assert.equal(originOf('https://a.test:8443/x?y=1'), 'https://a.test:8443')
  assert.equal(originOf('{{baseUrl}}/x'), null)
  assert.equal(originOf('ftp://a.test'), null)
  assert.equal(originOf('nonsense'), null)
})

test('a url that starts with a variable shows no TLS stage unless TLS is what failed', () => {
  const refused = describeError('Connection refused', '{{baseUrl}}/a')
  assert.deepEqual(stageRows(refused, '{{baseUrl}}/a').map((row) => row.key), ['dns', 'connect', 'request', 'response'])
  const tls = describeError('TLS handshake failed: PKIX path building failed', '{{baseUrl}}/a')
  assert.ok(stageRows(tls, '{{baseUrl}}/a').some((row) => row.key === 'tls'))
})
