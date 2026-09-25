/**
 * `{{name}}` completion: which placeholder the cursor is in, which names fill it, and the
 * text once one is picked.
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  applyCompletion,
  matchOptions,
  openPlaceholder,
  variableOptions
} from '../src/renderer/src/lib/completion.ts'

test('two braces open a placeholder with an empty query', () => {
  assert.deepEqual(openPlaceholder('https://{{', 10), { from: 10, to: 10, query: '' })
})

test('the query is the name typed so far', () => {
  const text = 'https://{{ba/users'
  assert.deepEqual(openPlaceholder(text, 12), { from: 10, to: 12, query: 'ba' })
})

test('whitespace after the braces is allowed, as the core trims it', () => {
  assert.deepEqual(openPlaceholder('{{  tok', 7), { from: 4, to: 7, query: 'tok' })
})

test('a single brace, a closed placeholder, or a space in the name is not open', () => {
  assert.equal(openPlaceholder('{', 1), null)
  assert.equal(openPlaceholder('{{done}}', 8), null)
  assert.equal(openPlaceholder('{{a b', 5), null)
  assert.equal(openPlaceholder('plain text', 10), null)
})

test('only the placeholder the cursor is in counts', () => {
  const text = '{{host}}/{{pa'
  assert.deepEqual(openPlaceholder(text, text.length), { from: 11, to: 13, query: 'pa' })
  assert.equal(openPlaceholder(text, 8), null)
})

test('a new line ends the name', () => {
  assert.equal(openPlaceholder('{{a\nb', 5), null)
})

test('each name is labelled with the scope that wins it on the wire', () => {
  const options = variableOptions({
    resolved: { host: 'api.test', token: 'env-token', page: '1' },
    environment: ['token'],
    runtime: ['session', 'page'],
    secrets: ['token', 'api-key']
  })
  assert.deepEqual(options, [
    { name: 'api-key', source: 'secret' },
    { name: 'host', source: 'collection', value: 'api.test' },
    { name: 'page', source: 'runtime' },
    { name: 'session', source: 'runtime' },
    { name: 'token', source: 'secret' }
  ])
})

test('prefix matches come before names that merely contain the query', () => {
  const options = variableOptions({
    resolved: { 'auth-token': '', token: '', user: '' },
    environment: [],
    runtime: [],
    secrets: []
  })
  assert.deepEqual(
    matchOptions(options, 'TOK').map((option) => option.name),
    ['token', 'auth-token']
  )
  assert.equal(matchOptions(options, '').length, 3)
})

test('picking a name closes the placeholder and moves past it', () => {
  const text = 'https://{{ba/users'
  const placeholder = openPlaceholder(text, 12)!
  assert.deepEqual(applyCompletion(text, placeholder, 'baseUrl'), {
    text: 'https://{{baseUrl}}/users',
    cursor: 19
  })
})

test('braces an editor already paired are reused, not doubled', () => {
  const text = '{"a": "{{}}"}'
  const placeholder = openPlaceholder(text, 9)!
  assert.deepEqual(applyCompletion(text, placeholder, 'id'), {
    text: '{"a": "{{id}}"}',
    cursor: 13
  })
})

test('the rest of a name the cursor sits inside is replaced', () => {
  const text = '{{toXXX}} tail'
  const placeholder = openPlaceholder(text, 4)!
  assert.deepEqual(applyCompletion(text, placeholder, 'token'), {
    text: '{{token}} tail',
    cursor: 9
  })
})
