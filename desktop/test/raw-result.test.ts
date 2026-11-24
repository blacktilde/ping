/** Large core responses cross as text; anything the shell must inspect is still parsed. */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { hasCapturedEntries, peekResult, RAW_THRESHOLD } from '../src/main/raw-result.ts'

const big = 'x'.repeat(RAW_THRESHOLD)

test('a large success line yields its id and the raw result', () => {
  const result = `{"status":200,"body":"${big}"}`
  const line = `{"jsonrpc":"2.0","id":42,"result":${result}}`
  assert.deepEqual(peekResult(line), { id: 42, json: result })
})

test('small lines, errors, notifications and odd shapes are left to JSON.parse', () => {
  assert.equal(peekResult('{"jsonrpc":"2.0","id":1,"result":{"ok":true}}'), null)
  assert.equal(peekResult(`{"jsonrpc":"2.0","id":1,"error":{"code":1,"message":"${big}"}}`), null)
  assert.equal(peekResult(`{"jsonrpc":"2.0","method":"x","params":{"a":"${big}"}}`), null)
  assert.equal(peekResult(`{"id":1,"jsonrpc":"2.0","result":"${big}"}`), null)
  assert.equal(peekResult(`{"jsonrpc":"2.0","id":1,"result":"${big}"} `), null)
})

test('the raw result parses to what the whole line would have', () => {
  const line = `{"jsonrpc":"2.0","id":7,"result":{"body":"${big}","n":[1,2]}}`
  const peeked = peekResult(line)
  assert.deepEqual(JSON.parse(peeked!.json), JSON.parse(line).result)
})

test('only an empty captured list is safe to pass through', () => {
  assert.equal(hasCapturedEntries('{"status":200}'), false)
  assert.equal(hasCapturedEntries('{"captured":[]}'), false)
  assert.equal(hasCapturedEntries('{"captured":[{"name":"t","found":true,"value":"s"}]}'), true)
  assert.equal(hasCapturedEntries('{"captured":null}'), true)
  assert.equal(hasCapturedEntries('{"captured":[],"x":{"captured":[{"value":"s"}]}}'), true)
})

test('a body that mentions captured cannot trip or hide the check', () => {
  const body = JSON.stringify({ body: '"captured":[{"value":"s"}]' })
  assert.equal(hasCapturedEntries(body), false)
  const hidden = JSON.stringify({ body: '"captured":[]' }).slice(0, -1) + ',"captured":[{"value":"s"}]}'
  assert.equal(hasCapturedEntries(hidden), true)
})
