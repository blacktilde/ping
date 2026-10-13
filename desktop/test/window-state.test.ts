/**
 * The window reopens at the size and place the user left it, unless that place is gone.
 * Run with Node's own test runner (`npm test`); Node strips the types.
 */
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { readWindowState, restoreBounds } from '../src/main/window-state.ts'

const fallback = { width: 1280, height: 820 }
const min = { width: 900, height: 600 }
const screen = [{ x: 0, y: 0, width: 1920, height: 1080 }]

function scratch(): string {
  return mkdtempSync(join(tmpdir(), 'ping-window-state-'))
}

test('a saved size and position are read back from the file', () => {
  const dir = scratch()
  try {
    const file = join(dir, 'window-state.json')
    const state = { width: 1000, height: 700, x: 40, y: 60, maximized: true }
    writeFileSync(file, JSON.stringify(state))
    assert.deepEqual(readWindowState(file), state)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('a missing, corrupt or nonsensical file means no saved state', () => {
  const dir = scratch()
  try {
    const file = join(dir, 'window-state.json')
    assert.equal(readWindowState(file), null)
    writeFileSync(file, '{not json')
    assert.equal(readWindowState(file), null)
    writeFileSync(file, JSON.stringify({ width: -5, height: 700 }))
    assert.equal(readWindowState(file), null)
    writeFileSync(file, JSON.stringify({ width: 'wide', height: 700 }))
    assert.equal(readWindowState(file), null)
    writeFileSync(file, 'null')
    assert.equal(readWindowState(file), null)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('a bad position is dropped but the size is kept', () => {
  const dir = scratch()
  try {
    const file = join(dir, 'window-state.json')
    writeFileSync(file, JSON.stringify({ width: 1000, height: 700, x: 'left', y: 10 }))
    assert.deepEqual(readWindowState(file), { width: 1000, height: 700, maximized: false })
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('with nothing saved the default size is used and the OS places the window', () => {
  assert.deepEqual(restoreBounds(null, fallback, min, screen), fallback)
})

test('a saved size and a position on a connected display are restored', () => {
  const saved = { width: 1000, height: 700, x: 100, y: 50, maximized: false }
  assert.deepEqual(restoreBounds(saved, fallback, min, screen), { width: 1000, height: 700, x: 100, y: 50 })
})

test('a position on a display that is gone is dropped, the size is not', () => {
  const saved = { width: 1000, height: 700, x: 3000, y: 100, maximized: false }
  assert.deepEqual(restoreBounds(saved, fallback, min, screen), { width: 1000, height: 700 })
})

test('a position on a second display is kept while that display is connected', () => {
  const saved = { width: 1000, height: 700, x: 2000, y: 100, maximized: false }
  const two = [...screen, { x: 1920, y: 0, width: 1920, height: 1080 }]
  assert.deepEqual(restoreBounds(saved, fallback, min, two), { width: 1000, height: 700, x: 2000, y: 100 })
})

test('a saved size below the window minimum is raised to it', () => {
  const saved = { width: 300, height: 200, maximized: false }
  assert.deepEqual(restoreBounds(saved, fallback, min, screen), { width: 900, height: 600 })
})
