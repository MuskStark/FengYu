import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { inspectArchive, MAX_PACKAGE_BYTES } from '../src/archive.mjs'

const table = (() => {
  const t = []
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = (c & 1) ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()
function crc32(data) {
  let c = 0xffffffff
  for (const b of data) c = table[(c ^ b) & 255] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const u16 = (n) => { const b = Buffer.alloc(2); b.writeUInt16LE(n); return b }
const u32 = (n) => { const b = Buffer.alloc(4); b.writeUInt32LE(n >>> 0); return b }

/** Build a store-mode zip with the given entry list [{name, data}]. */
function buildZip(entries) {
  const locals = [], centrals = []
  let offset = 0
  for (const f of entries) {
    const name = Buffer.from(f.name)
    const data = Buffer.from(f.data)
    const crc = crc32(data)
    const local = Buffer.concat([u32(0x04034b50), u16(20), u16(0), u16(0), u16(0), u16(0), u32(crc), u32(data.length), u32(data.length), u16(name.length), u16(0), name, data])
    locals.push(local)
    centrals.push(Buffer.concat([u32(0x02014b50), u16(20), u16(20), u16(0), u16(0), u16(0), u16(0), u32(crc), u32(data.length), u32(data.length), u16(name.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(offset), name]))
    offset += local.length
  }
  const central = Buffer.concat(centrals)
  const end = Buffer.concat([u32(0x06054b50), u16(0), u16(0), u16(entries.length), u16(entries.length), u32(central.length), u32(offset), u16(0)])
  return Buffer.concat([...locals, central, end])
}

let base
test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-archive-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

async function writeZip(name, entries) {
  const file = path.join(base, name)
  await fs.writeFile(file, buildZip(entries))
  return file
}

test('inspects a normal archive and reports entry metadata', async () => {
  const file = await writeZip('normal.zip', [
    { name: 'manifest.json', data: '{}' },
    { name: 'ui/index.html', data: '<html></html>' },
  ])
  const result = await inspectArchive(file)
  assert.equal(result.entries.length, 2)
  assert.equal(result.totalExpandedBytes, '{}'.length + '<html></html>'.length)
})

test('rejects zip-slip traversal entries', async () => {
  const file = await writeZip('slip.zip', [{ name: '../escape.txt', data: 'bad' }])
  await assert.rejects(() => inspectArchive(file), /unsafe archive path/)
})

test('rejects duplicate entries', async () => {
  const file = await writeZip('dup.zip', [
    { name: 'a.txt', data: '1' },
    { name: 'a.txt', data: '2' },
  ])
  await assert.rejects(() => inspectArchive(file), /duplicate archive entry/)
})

test('rejects an archive expanding past 300 MB', async () => {
  // Declare an entry larger than the limit without materialising the bytes.
  const name = Buffer.from('big.bin')
  const local = Buffer.concat([u32(0x04034b50), u16(20), u16(0), u16(0), u16(0), u16(0), u32(0), u32(350 * 1024 * 1024), u32(350 * 1024 * 1024), u16(name.length), u16(0), name])
  const central = Buffer.concat([u32(0x02014b50), u16(20), u16(20), u16(0), u16(0), u16(0), u16(0), u32(0), u32(350 * 1024 * 1024), u32(350 * 1024 * 1024), u16(name.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(0), name])
  const end = Buffer.concat([u32(0x06054b50), u16(0), u16(0), u16(1), u16(1), u32(central.length), u32(local.length), u16(0)])
  const file = path.join(base, 'oversized.zip')
  await fs.writeFile(file, Buffer.concat([local, central, end]))
  await assert.rejects(() => inspectArchive(file), /exceeds 300 MB/)
})

test('rejects an archive file larger than 100 MB', async () => {
  const file = path.join(base, 'package-too-large.fyp')
  const handle = await fs.open(file, 'w')
  await handle.truncate(MAX_PACKAGE_BYTES + 1)
  await handle.close()
  await assert.rejects(() => inspectArchive(file), /package exceeds 100 MB/)
})

test('rejects absolute archive entry paths', async () => {
  const file = await writeZip('absolute.zip', [{ name: '/absolute.txt', data: 'x' }])
  await assert.rejects(() => inspectArchive(file), /unsafe archive path/)
})
