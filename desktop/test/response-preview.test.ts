/**
 * What decides whether a binary response body is previewed in place. Run with Node's own
 * test runner (`npm test`); Node strips the types.
 */

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { bytesFromBase64, isHtml, isPdf } from '../src/renderer/src/lib/response.ts'

test('a PDF is recognized with or without content-type parameters', () => {
  assert.equal(isPdf('application/pdf'), true)
  assert.equal(isPdf('application/pdf; charset=binary'), true)
  assert.equal(isPdf('  Application/PDF  '), true)
  assert.equal(isPdf('application/x-pdf'), true)
})

test('nothing else is offered to the PDF viewer', () => {
  assert.equal(isPdf('application/octet-stream'), false)
  assert.equal(isPdf('text/html'), false)
  // A type that merely mentions pdf is not one: the viewer would fail on the bytes.
  assert.equal(isPdf('application/pdf-thing'), false)
  assert.equal(isPdf('application/json;x=application/pdf'), false)
  assert.equal(isPdf(null), false)
  assert.equal(isPdf(undefined), false)
})

test('HTML and PDF stay separate previews', () => {
  assert.equal(isHtml('application/pdf'), false)
})

test('base64 decodes to the exact bytes, high bytes included', () => {
  const bytes = Uint8Array.from([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34, 0x00, 0xff, 0x80])
  const base64 = Buffer.from(bytes).toString('base64')
  assert.deepEqual(bytesFromBase64(base64), bytes)
})

test('an empty body decodes to no bytes', () => {
  assert.deepEqual(bytesFromBase64(''), new Uint8Array(0))
})
