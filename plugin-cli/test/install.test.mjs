import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { installPlugin } from '../src/install.mjs'

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

const goodManifest = JSON.stringify({
  schemaVersion: 1, id: 'com.example.install', name: 'Install', version: '1.0.0',
  ui: { entry: 'ui/index.html' }, permissions: [], official: false, aiTools: [],
})

let base
test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-install-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

async function writeFyp(name, entries) {
  const file = path.join(base, name.endsWith('.fyp') ? name : name + '.fyp')
  await fs.writeFile(file, buildZip(entries))
  return file
}

test('rejects a non-fyp extension', async () => {
  const file = path.join(base, 'not-a-zip.txt')
  await fs.writeFile(file, 'nope')
  await assert.rejects(() => installPlugin(file, { host: 'http://127.0.0.1:9', fetchImpl: async () => new Response() }), /\.fyp/)
})

test('rejects an unsafe (zip-slip) archive before any fetch', async () => {
  let fetched = 0
  const fetchImpl = async () => { fetched++; return new Response('{}', { status: 200 }) }
  const file = await writeFyp('slip', [{ name: '../escape.txt', data: 'bad' }])
  await assert.rejects(() => installPlugin(file, { host: 'http://127.0.0.1:9', fetchImpl }), /unsafe archive path/)
  assert.equal(fetched, 0)
})

test('rejects a package missing manifest.json before any fetch', async () => {
  let fetched = 0
  const fetchImpl = async () => { fetched++; return new Response() }
  const file = await writeFyp('no-manifest', [{ name: 'ui/index.html', data: '<html></html>' }])
  await assert.rejects(() => installPlugin(file, { host: 'http://127.0.0.1:9', fetchImpl }), /manifest/)
  assert.equal(fetched, 0)
})

test('uploads a valid package and parses the JSON response', async () => {
  let received
  const fetchImpl = async (url, init) => {
    received = { url, init }
    return new Response(JSON.stringify({ id: 'com.example.install', ok: true }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  const file = await writeFyp('good', [
    { name: 'manifest.json', data: goodManifest },
    { name: 'ui/index.html', data: '<html></html>' },
  ])
  const result = await installPlugin(file, { host: 'http://127.0.0.1:24056', token: 'tok', fetchImpl })
  assert.deepEqual(result, { id: 'com.example.install', ok: true })
  assert.equal(received.url, 'http://127.0.0.1:24056/api/plugin-market/upload')
  assert.equal(received.init.headers['X-FengYu-Token'], 'tok')
  assert.ok(received.init.body instanceof FormData)
})

test('falls back to FENGYU_TOKEN env when no token option is given', async () => {
  process.env.FENGYU_TOKEN = 'env-tok'
  try {
    const fetchImpl = async (url, init) => {
      assert.equal(init.headers['X-FengYu-Token'], 'env-tok')
      return new Response('installed', { status: 200 })
    }
    const file = await writeFyp('envtok', [
      { name: 'manifest.json', data: goodManifest },
      { name: 'ui/index.html', data: '<html></html>' },
    ])
    const result = await installPlugin(file, { host: 'http://127.0.0.1:24056', fetchImpl })
    assert.equal(result, 'installed')
  } finally {
    delete process.env.FENGYU_TOKEN
  }
})

test('preserves the host error body on non-2xx', async () => {
  const fetchImpl = async () => new Response('denied', { status: 403 })
  const file = await writeFyp('denied', [
    { name: 'manifest.json', data: goodManifest },
    { name: 'ui/index.html', data: '<html></html>' },
  ])
  await assert.rejects(() => installPlugin(file, { host: 'http://127.0.0.1:24056', fetchImpl }), /403 denied/)
})
