/**
 * The framing between the main process and the core: one JSON message per line, arriving in
 * pipe chunks that ignore line boundaries.
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { LineSplitter } from '../src/main/lines.ts'

test('a line split across chunks comes out whole', () => {
  const lines = new LineSplitter()
  assert.deepEqual(lines.push('{"id":'), [])
  assert.deepEqual(lines.push('1,"result"'), [])
  assert.deepEqual(lines.push(':true}\n'), ['{"id":1,"result":true}'])
})

test('one chunk can finish a line, carry whole ones, and start the next', () => {
  const lines = new LineSplitter()
  lines.push('{"a":')
  assert.deepEqual(lines.push('1}\n{"b":2}\n{"c":'), ['{"a":1}', '{"b":2}'])
  assert.deepEqual(lines.push('3}\n'), ['{"c":3}'])
})

test('blank lines and carriage returns are dropped', () => {
  const lines = new LineSplitter()
  assert.deepEqual(lines.push('\n\r\n  {"a":1}\r\n\n'), ['{"a":1}'])
})

test('a chunk that ends exactly on a newline leaves nothing behind', () => {
  const lines = new LineSplitter()
  assert.deepEqual(lines.push('{"a":1}\n'), ['{"a":1}'])
  assert.deepEqual(lines.push('{"b":2}\n'), ['{"b":2}'])
})

test('a response at the body limit reads in linear time', () => {
  // A 10 MB binary body is about 13 MB of base64 on one line; the old buffer rescan took
  // about a second on it. Chunks are the size a pipe delivers.
  const line = JSON.stringify({ id: 7, result: { base64: 'A'.repeat(14_000_000) } })
  const payload = `${line}\n`
  const lines = new LineSplitter()
  const started = performance.now()
  const out: string[] = []
  for (let offset = 0; offset < payload.length; offset += 65536) {
    out.push(...lines.push(payload.slice(offset, offset + 65536)))
  }
  const elapsed = performance.now() - started
  assert.equal(out.length, 1)
  assert.equal(out[0], line)
  assert.ok(elapsed < 250, `took ${elapsed.toFixed(0)} ms`)
})
