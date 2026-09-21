/**
 * An installer's name has to mean the same thing on both ends of the update feed. GitHub
 * stores an uploaded release asset with its spaces turned into dots, and electron-updater
 * rewrites the same spaces as hyphens when it resolves a url out of `latest.yml`, so a name
 * with a space in it is a download that 404s — visible only to someone already running the
 * app. electron-builder's NSIS default has two. Keep the configured names free of them.
 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const config = readFileSync(new URL('../electron-builder.yml', import.meta.url), 'utf8')

/** The lines of one top-level block, up to the next key in the first column. */
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
