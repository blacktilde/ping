/**
 * What the truncation banner offers when a response outgrows its display cap. Run with Node's
 * own test runner (`npm test`); Node strips the types.
 */

import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  DEFAULT_DISPLAY_CAP,
  isSafeMethod,
  MAX_OFFERED_CAP,
  raisedCap
} from '../src/renderer/src/lib/response.ts'

const MB = 1024 * 1024

test('the offered cap is the next power-of-two megabytes that holds the whole body', () => {
  assert.equal(raisedCap(48.3 * MB, DEFAULT_DISPLAY_CAP), 64 * MB)
  assert.equal(raisedCap(10 * MB + 1, DEFAULT_DISPLAY_CAP), 16 * MB)
  assert.equal(raisedCap(32 * MB, DEFAULT_DISPLAY_CAP), 32 * MB)
  // A small custom cap is raised from a megabyte, not from the default.
  assert.equal(raisedCap(3000, 1024), MB)
})

test('past the next power of two, the ceiling itself is offered while it still fits', () => {
  assert.equal(raisedCap(90 * MB, DEFAULT_DISPLAY_CAP), MAX_OFFERED_CAP)
  assert.equal(raisedCap(MAX_OFFERED_CAP, DEFAULT_DISPLAY_CAP), MAX_OFFERED_CAP)
})

test('nothing is offered for a body too large to display, or one that already fits', () => {
  assert.equal(raisedCap(MAX_OFFERED_CAP + 1, DEFAULT_DISPLAY_CAP), null)
  assert.equal(raisedCap(2 * 1024 * MB, DEFAULT_DISPLAY_CAP), null)
  assert.equal(raisedCap(5 * MB, DEFAULT_DISPLAY_CAP), null)
  assert.equal(raisedCap(Number.NaN, DEFAULT_DISPLAY_CAP), null)
})

test('only methods that cannot change server state resend without asking', () => {
  for (const method of ['GET', 'head', ' OPTIONS ', 'TRACE']) {
    assert.equal(isSafeMethod(method), true, method)
  }
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE', '']) {
    assert.equal(isSafeMethod(method), false, method)
  }
})
