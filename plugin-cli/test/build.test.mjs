import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createPlugin } from '../src/create.mjs'
import { buildPlugin } from '../src/build.mjs'
import { assembleStaging } from '../src/staging.mjs'
import { detectProject } from '../src/project.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const staticFixture = path.resolve(__dirname, 'fixtures/static-plugin')

let base
let root

test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-build-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

/** A declared project whose fengyu.plugin.json drives the full lifecycle. */
async function makeDeclaredProject() {
  const dir = path.join(base, `declared-${Date.now()}-${Math.random().toString(36).slice(2)}`)
  await fs.mkdir(dir, { recursive: true })
  await fs.writeFile(path.join(dir, 'manifest.json'), JSON.stringify({
    schemaVersion: 1, id: 'com.example.declared', name: 'Declared', version: '1.0.0',
    ui: { entry: 'ui/index.html' },
    backend: { command: 'java -jar backend/worker.jar', protocol: 'json-rpc-2.0' },
    permissions: [], official: false, aiTools: [],
  }))
  await fs.writeFile(path.join(dir, 'fengyu.plugin.json'), JSON.stringify({
    schemaVersion: 1,
    ui: {
      root: 'ui-src', output: 'dist',
      prepare: [['node', '-e', '0']],
      install: ['npm', 'ci'], test: ['npm', 'test'], build: ['npm', 'run', 'build'],
    },
    worker: {
      root: 'worker', test: ['maven', 'test'],
      build: ['maven', 'package', '-DskipTests'],
      artifact: 'worker/target/declared-worker.jar',
      mainClass: 'com.example.DeclaredWorkerMain',
    },
    package: {
      outputDirectory: 'dist-package',
      resources: [{ from: 'assets', to: 'runtime-assets' }],
    },
  }))
  await fs.mkdir(path.join(dir, 'assets'), { recursive: true })
  await fs.writeFile(path.join(dir, 'assets/example.txt'), 'example')
  // A stub mvnw so resolveCommand finds the wrapper in the worker root.
  await fs.mkdir(path.join(dir, 'worker'), { recursive: true })
  await fs.writeFile(path.join(dir, 'worker/mvnw'), '#!/bin/sh\nexec mvn "$@"\n', { mode: 0o755 })
  return dir
}

/**
 * Injected runner that simulates the configured commands, producing the UI
 * output and worker artifact they claim to, and recording invocation order.
 */
function fakeRunner(order, dir) {
  return async (command, args, options) => {
    const tag = tagFor(command, args, options?.cwd ?? '')
    order.push(tag)
    if (tag === 'ui-build') {
      await fs.mkdir(path.join(dir, 'ui-src/dist'), { recursive: true })
      await fs.writeFile(path.join(dir, 'ui-src/dist/index.html'), '<div id="app"></div>')
    }
    if (tag === 'worker-build') {
      await fs.mkdir(path.join(dir, 'worker/target'), { recursive: true })
      await makeFakeJar(path.join(dir, 'worker/target/declared-worker.jar'), 'com.example.DeclaredWorkerMain')
    }
    return { code: 0 }
  }
}

function tagFor(command, args, cwd) {
  const isMvnw = /mvnw$/.test(command)
  const joined = [command, ...args].join(' ')
  if (joined.startsWith('node ')) return 'ui-prepare'
  // A fresh project installs via `npm install` (no lockfile yet); once a lockfile
  // exists the configured `npm ci` runs. Either way this is the install phase.
  if ((joined === 'npm ci' || joined === 'npm install') && cwd.endsWith('ui-src')) return 'ui-install'
  if (joined === 'npm test' && cwd.endsWith('ui-src')) return 'ui-test'
  if (isMvnw && args.includes('test')) return 'worker-test'
  if (joined === 'npm run build') return 'ui-build'
  if (isMvnw && args.includes('package')) return 'worker-build'
  return joined
}

/** Write a minimal runnable-style JAR (store-mode zip) with a manifest + class entry. */
async function makeFakeJar(file, mainClass) {
  const options = typeof mainClass === 'string'
    ? { classEntry: mainClass, manifestMainClass: mainClass }
    : mainClass
  const classEntry = options.classEntry.replace(/\./g, '/') + '.class'
  const entries = [
    { name: 'META-INF/MANIFEST.MF', data: `Manifest-Version: 1.0\nMain-Class: ${options.manifestMainClass}\n\n` },
    { name: classEntry, data: Buffer.from([0xca, 0xfe, 0xba, 0xbe]) },
  ]
  await fs.mkdir(path.dirname(file), { recursive: true })
  await fs.writeFile(file, buildZipBytes(entries))
}

test.beforeEach(async () => { root = await makeDeclaredProject() })

test('declared lifecycle runs phases in the exact required order', async () => {
  const order = []
  const result = await buildPlugin(root, { run: fakeRunner(order, root) })
  assert.deepEqual(order, [
    'ui-prepare', 'ui-install', 'ui-test', 'worker-test', 'ui-build', 'worker-build',
  ])
  assert.ok(result.output.endsWith('.fyp'))
  assert.ok(await fs.stat(result.output))
})

test('skipTests omits both test phases but keeps build/package', async () => {
  const order = []
  await buildPlugin(root, { run: fakeRunner(order, root), skipTests: true })
  assert.deepEqual(order, ['ui-prepare', 'ui-install', 'ui-build', 'worker-build'])
})

test('ui build failure leaves no fyp, tmp, or staging', async () => {
  const order = []
  await assert.rejects(
    () => buildPlugin(root, {
      run: async (command, args, options) => {
        const tag = tagFor(command, args, options?.cwd ?? '')
        order.push(tag)
        if (tag === 'ui-build') throw Object.assign(new Error('vite failed'), { code: 2 })
        if (tag === 'worker-build') await makeFakeJar(path.join(root, 'worker/target/declared-worker.jar'), 'x')
      },
    }),
    /vite failed/,
  )
  const pkgDir = path.join(root, 'dist-package')
  const leftovers = (await fs.readdir(pkgDir).catch(() => [])).filter((x) => x.endsWith('.fyp') || x.startsWith('.staging-') || x.includes('.tmp-'))
  assert.deepEqual(leftovers, [])
})

test('worker build failure leaves no fyp, tmp, or staging', async () => {
  await assert.rejects(
    () => buildPlugin(root, {
      run: async (command, args, options) => {
        const tag = tagFor(command, args, options?.cwd ?? '')
        if (tag === 'ui-build') {
          await fs.mkdir(path.join(root, 'ui-src/dist'), { recursive: true })
          await fs.writeFile(path.join(root, 'ui-src/dist/index.html'), '<div></div>')
        }
        if (tag === 'worker-build') throw Object.assign(new Error('mvn failed'), { code: 1 })
      },
    }),
    /mvn failed/,
  )
  const pkgDir = path.join(root, 'dist-package')
  const leftovers = (await fs.readdir(pkgDir).catch(() => [])).filter((x) => x.endsWith('.fyp') || x.startsWith('.staging-') || x.includes('.tmp-'))
  assert.deepEqual(leftovers, [])
})

test('missing worker artifact fails validation with no partial output', async () => {
  await assert.rejects(
    () => buildPlugin(root, {
      run: async (command, args, options) => {
        const tag = tagFor(command, args, options?.cwd ?? '')
        if (tag === 'ui-build') {
          await fs.mkdir(path.join(root, 'ui-src/dist'), { recursive: true })
          await fs.writeFile(path.join(root, 'ui-src/dist/index.html'), '<div></div>')
        }
        // worker-build produces nothing → artifact missing
      },
    }),
    /worker/,
  )
  const pkgDir = path.join(root, 'dist-package')
  const leftovers = (await fs.readdir(pkgDir).catch(() => [])).filter((x) => x.endsWith('.fyp'))
  assert.deepEqual(leftovers, [])
})

test('worker Main-Class must match the declared build configuration', async () => {
  await assert.rejects(
    () => buildPlugin(root, {
      run: async (command, args, options) => {
        const tag = tagFor(command, args, options?.cwd ?? '')
        if (tag === 'ui-build') {
          await fs.mkdir(path.join(root, 'ui-src/dist'), { recursive: true })
          await fs.writeFile(path.join(root, 'ui-src/dist/index.html'), '<div></div>')
        }
        if (tag === 'worker-build') {
          await makeFakeJar(path.join(root, 'worker/target/declared-worker.jar'), {
            classEntry: 'com.example.DeclaredWorkerMain',
            manifestMainClass: 'com.example.WrongMain',
          })
        }
      },
    }),
    /worker JAR Main-Class .* does not match com\.example\.DeclaredWorkerMain/,
  )
})

test('assembleStaging copies manifest, ui output, and worker jar only', async () => {
  // Drive the build once to produce outputs, then inspect staging.
  await buildPlugin(root, { run: fakeRunner([], root) })
  const project = await detectProject(root)
  const staging = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-stage-'))
  try {
    await assembleStaging(project, staging)
    const entries = await walk(staging)
    assert.ok(entries.includes('manifest.json'))
    assert.ok(entries.includes('ui/index.html'))
    assert.ok(entries.includes('backend/worker.jar'))
    assert.ok(entries.includes('runtime-assets/example.txt'))
    assert.equal(entries.some((entry) => entry.includes(root.replace(/^\//, ''))), false)
    // No source/tooling smuggled in.
    assert.ok(!entries.some((e) => e.startsWith('ui-src/') || e.includes('fengyu.plugin.json') || e.includes('node_modules')))
  } finally {
    await fs.rm(staging, { recursive: true, force: true }).catch(() => {})
  }
})

test('legacy static plugin still packages via staging', async () => {
  const out = path.join(base, `legacy-${Date.now()}.fyp`)
  await buildPlugin(staticFixture, { out, run: async () => assert.fail('must not run npm') })
  assert.ok(await fs.stat(out))
})

test('legacy vue-vite plugin runs frontend build then packages', async () => {
  const dir = path.join(base, `vite-${Date.now()}`)
  await createPlugin(dir, 'com.example.vite', { install: false, uiOnly: true, run: async () => {} })
  const order = []
  const out = path.join(base, `vite-out-${Date.now()}.fyp`)
  await buildPlugin(dir, {
    out,
    run: async () => {
      order.push('frontend')
      await fs.mkdir(path.join(dir, 'ui'), { recursive: true })
      await fs.writeFile(path.join(dir, 'ui/index.html'), '<div id="app"></div>')
    },
  })
  assert.deepEqual(order, ['frontend'])
  assert.ok(await fs.stat(out))
})

async function walk(dir, base = dir, out = []) {
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) await walk(full, base, out)
    else out.push(path.relative(base, full).split(path.sep).join('/'))
  }
  return out
}

const CRC_TABLE = (() => {
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
  for (const b of data) c = CRC_TABLE[(c ^ b) & 255] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const u16 = (n) => { const b = Buffer.alloc(2); b.writeUInt16LE(n); return b }
const u32 = (n) => { const b = Buffer.alloc(4); b.writeUInt32LE(n >>> 0); return b }
function buildZipBytes(entries) {
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
