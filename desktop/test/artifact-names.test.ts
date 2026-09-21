import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const config = readFileSync(new URL('../electron-builder.yml', import.meta.url), 'utf8')

function block(key: string): string {
  const start = config.indexOf(`\n${key}:\n`)
  assert.notEqual(start, -1, `electron-builder.yml has no ${key} block`)
  const rest = config.slice(start + 1)
  const end = rest.search(/\n[a-z]/)
  return end === -1 ? rest : rest.slice(0, end)
}

function artifactNames(source: string): string[] {
  return [...source.matchAll(/^\s*artifactName:\s*(\S.*?)\s*$/gm)].map((match) => match[1])
}

test('every configured artifact name is free of spaces', () => {
  const names = artifactNames(config)
  assert.ok(names.length > 0, 'expected at least one artifactName to be pinned')
  for (const name of names) {
    assert.ok(!name.includes(' '), `${name} has a space, so the update feed cannot resolve it`)
  }
})

test('windows pins an artifact name, because its default has spaces', () => {
  assert.equal(artifactNames(block('win')).length, 1)
})
