import assert from 'node:assert/strict'
import { test } from 'node:test'
import { moveItem } from '../src/renderer/src/lib/reorder.ts'

test('moving forward lands before the target', () => {
  assert.deepEqual(moveItem(['a', 'b', 'c', 'd'], 0, 3), ['b', 'c', 'a', 'd'])
})

test('moving backward lands before the target', () => {
  assert.deepEqual(moveItem(['a', 'b', 'c', 'd'], 3, 1), ['a', 'd', 'b', 'c'])
})

test('moving to the end', () => {
  assert.deepEqual(moveItem(['a', 'b', 'c'], 0, 3), ['b', 'c', 'a'])
})

test('dropping on its own position changes nothing', () => {
  assert.deepEqual(moveItem(['a', 'b', 'c'], 1, 1), ['a', 'b', 'c'])
  assert.deepEqual(moveItem(['a', 'b', 'c'], 1, 2), ['a', 'b', 'c'])
})

test('an unknown source leaves the list alone', () => {
  assert.deepEqual(moveItem(['a', 'b'], 5, 0), ['a', 'b'])
})
