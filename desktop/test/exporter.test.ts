/**
 * The shell's half of a collection export: file names, the check on what the core returned,
 * and the report the renderer sees.
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { exportFileName, exportReport, readExport, uniqueFileNames } from '../src/main/exporter.ts'

test('the suggested name carries the Postman suffix', () => {
  assert.equal(exportFileName('Shop API'), 'Shop API.postman_collection.json')
})

test('characters a file system refuses are replaced', () => {
  assert.equal(exportFileName('a/b:c*?'), 'a b c.postman_collection.json')
  assert.equal(exportFileName('../../etc'), '.. .. etc.postman_collection.json')
  assert.equal(exportFileName('  '), 'collection.postman_collection.json')
})

test('collections sharing a name get distinct files', () => {
  assert.deepEqual(uniqueFileNames(['Shop', 'shop', 'Blog'], () => false), [
    'Shop.postman_collection.json',
    'shop 2.postman_collection.json',
    'Blog.postman_collection.json'
  ])
})

test('a file already in the folder is never overwritten', () => {
  const existing = new Set(['Shop.postman_collection.json', 'Shop 2.postman_collection.json'])
  assert.deepEqual(uniqueFileNames(['Shop'], (name) => existing.has(name)), [
    'Shop 3.postman_collection.json'
  ])
})

test('a result without content is refused', () => {
  assert.throws(() => readExport({ name: 'x' }), /no export/)
  assert.throws(() => readExport(null), /no export/)
})

test('one collection keeps its warnings as they are, and never carries the content', () => {
  const exported = readExport({ name: 'Shop', content: '{}', requests: 3, warnings: ['one', 2] })
  const report = exportReport([{ exported, file: '/tmp/Shop.json' }])
  assert.deepEqual(report, {
    files: [{ name: 'Shop', requests: 3, file: '/tmp/Shop.json' }],
    warnings: ['one']
  })
  assert.equal(JSON.stringify(report).includes('{}'), false)
})

test('several collections name the collection each warning came from', () => {
  const shop = readExport({ name: 'Shop', content: '{}', requests: 1, warnings: ['no env'] })
  const blog = readExport({ name: 'Blog', content: '{}', requests: 2 })
  const report = exportReport([
    { exported: shop, file: '/out/Shop.json' },
    { exported: blog, file: '/out/Blog.json' }
  ])
  assert.deepEqual(report.warnings, ['Shop: no env'])
  assert.equal(report.files.length, 2)
})
