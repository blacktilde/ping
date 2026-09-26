/**
 * The shell's half of a collection export: the suggested file name and the check on what the
 * core returned.
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { exportFileName, exportReport, readExport } from '../src/main/exporter.ts'

test('the suggested name carries the Postman suffix', () => {
  assert.equal(exportFileName('Shop API'), 'Shop API.postman_collection.json')
})

test('characters a file system refuses are replaced', () => {
  assert.equal(exportFileName('a/b:c*?'), 'a b c.postman_collection.json')
  assert.equal(exportFileName('../../etc'), '.. .. etc.postman_collection.json')
  assert.equal(exportFileName('  '), 'collection.postman_collection.json')
})

test('a result without content is refused', () => {
  assert.throws(() => readExport({ name: 'x' }), /no export/)
  assert.throws(() => readExport(null), /no export/)
})

test('the report keeps the counts and only string warnings, never the content', () => {
  const exported = readExport({ name: 'Shop', content: '{}', requests: 3, warnings: ['one', 2] })
  const report = exportReport('/tmp/Shop.json', exported)
  assert.deepEqual(report, { file: '/tmp/Shop.json', name: 'Shop', requests: 3, warnings: ['one'] })
  assert.equal('content' in report, false)
})
