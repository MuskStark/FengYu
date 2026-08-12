import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {
  normalizeSemver,
  collectNpmToolingVersions,
  extractMavenSdkVersion,
  checkLockfile,
  checkToolchainVersionConsistency,
} from '../src/consistency.mjs'

// --- normalizeSemver --------------------------------------------------------

test('normalizeSemver extracts the X.Y.Z core from range syntax', () => {
  assert.equal(normalizeSemver('^1.3.0'), '1.3.0')
  assert.equal(normalizeSemver('~1.3.0'), '1.3.0')
  assert.equal(normalizeSemver('1.3.0'), '1.3.0')
  assert.equal(normalizeSemver('>=1.3.0 <2.0.0'), '1.3.0')
  assert.equal(normalizeSemver('1.3.0-beta.1'), '1.3.0')
})

test('normalizeSemver returns null for links, urls, and unresolvable specs', () => {
  for (const spec of [
    'file:../../../toolchain/sdk-ts',
    'workspace:*',
    'link:../x',
    'github:org/repo',
    'git+https://github.com/o/r.git',
    './local',
    '../up',
    '*',
    'latest',
    '',
    null,
    42,
  ]) {
    assert.equal(normalizeSemver(spec), null, `expected null for ${JSON.stringify(spec)}`)
  }
})

// --- collectNpmToolingVersions ---------------------------------------------

test('collectNpmToolingVersions keeps only concrete @infinia/* versions', () => {
  const out = collectNpmToolingVersions({
    dependencies: {
      '@infinia/plugin-sdk': 'file:../../../toolchain/sdk-ts',
      '@infinia/plugin-ui': '^1.3.0',
      vue: '3.5.39',
    },
    devDependencies: { '@infinia/plugin-dev': '~1.3.0', vite: '^7.1.3' },
  })
  assert.deepEqual(out, { '@infinia/plugin-ui': '1.3.0', '@infinia/plugin-dev': '1.3.0' })
})

test('collectNpmToolingVersions ignores non-infinia packages', () => {
  assert.deepEqual(collectNpmToolingVersions({ dependencies: { vue: '3.5.39' } }), {})
})

// --- extractMavenSdkVersion -------------------------------------------------

test('extractMavenSdkVersion resolves a ${property} against the pom properties', () => {
  const pom = `
    <project>
      <properties><fengyu.plugin.sdk.version>1.3.0</fengyu.plugin.sdk.version></properties>
      <dependencies>
        <dependency>
          <groupId>fan.summer.fengyu.sdk</groupId>
          <artifactId>fengyu-plugin-sdk</artifactId>
          <version>\${fengyu.plugin.sdk.version}</version>
        </dependency>
      </dependencies>
    </project>`
  assert.equal(extractMavenSdkVersion(pom), '1.3.0')
})

test('extractMavenSdkVersion reads a concrete inline version', () => {
  const pom = `
    <project><dependencies>
      <dependency>
        <groupId>fan.summer.fengyu.sdk</groupId>
        <artifactId>fengyu-plugin-sdk</artifactId>
        <version>1.2.0</version>
      </dependency>
    </dependencies></project>`
  assert.equal(extractMavenSdkVersion(pom), '1.2.0')
})

test('extractMavenSdkVersion returns null when the version is parent-managed (absent)', () => {
  const pom = `
    <project><dependencies>
      <dependency>
        <groupId>fan.summer.fengyu.sdk</groupId>
        <artifactId>fengyu-plugin-sdk</artifactId>
      </dependency>
    </dependencies></project>`
  assert.equal(extractMavenSdkVersion(pom), null)
})

test('extractMavenSdkVersion returns null when no sdk dependency exists', () => {
  assert.equal(extractMavenSdkVersion('<project><dependencies></dependencies></project>'), null)
})

// --- checkLockfile (filesystem) --------------------------------------------

let base
test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-cons-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

async function makeUiProject(extra) {
  const dir = path.join(base, `p-${Date.now()}-${Math.random().toString(36).slice(2)}`)
  await fs.mkdir(path.join(dir, 'ui-src'), { recursive: true })
  await fs.writeFile(
    path.join(dir, 'manifest.json'),
    JSON.stringify({
      schemaVersion: 2, id: 'com.example.x', name: 'x', description: 'd', version: '1.0.0',
      author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' },
    }),
  )
  await fs.writeFile(
    path.join(dir, 'ui-src/package.json'),
    JSON.stringify({
      dependencies: { '@infinia/plugin-ui': '^1.3.0' },
      ...extra,
    }),
  )
  return { dir, project: (await import('../src/project.mjs')).detectProject }
}

test('checkLockfile is a no-op when deps are not installed (fresh scaffold)', async () => {
  const { dir, project } = await makeUiProject()
  const p = await project(dir)
  assert.deepEqual(await checkLockfile(p), [])
})

test('checkLockfile flags node_modules without a lockfile', async () => {
  const { dir, project } = await makeUiProject()
  await fs.mkdir(path.join(dir, 'ui-src/node_modules'), { recursive: true })
  const p = await project(dir)
  const errors = await checkLockfile(p)
  assert.equal(errors.length, 1)
  assert.match(errors[0], /lockfile/)
  assert.match(errors[0], /npm install/)
})

test('checkLockfile passes when a lockfile is committed alongside node_modules', async () => {
  const { dir, project } = await makeUiProject()
  await fs.mkdir(path.join(dir, 'ui-src/node_modules'), { recursive: true })
  await fs.writeFile(path.join(dir, 'ui-src/package-lock.json'), '{}')
  const p = await project(dir)
  assert.deepEqual(await checkLockfile(p), [])
})

test('checkLockfile is a no-op for a static-UI project (no package.json root)', async () => {
  const dir = path.join(base, `static-${Date.now()}`)
  await fs.mkdir(path.join(dir, 'ui'), { recursive: true })
  await fs.writeFile(path.join(dir, 'ui/index.html'), '<html></html>')
  await fs.writeFile(
    path.join(dir, 'manifest.json'),
    JSON.stringify({
      schemaVersion: 2, id: 'com.example.s', name: 's', description: 'd', version: '1.0.0',
      author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' },
    }),
  )
  const { detectProject } = await import('../src/project.mjs')
  assert.deepEqual(await checkLockfile(await detectProject(dir)), [])
})

// --- checkToolchainVersionConsistency --------------------------------------

async function projectWith(dir, { npm, pom }) {
  await fs.mkdir(path.join(dir, 'ui-src'), { recursive: true })
  await fs.writeFile(
    path.join(dir, 'manifest.json'),
    JSON.stringify({
      schemaVersion: 2, id: 'com.example.t', name: 't', description: 'd', version: '1.0.0',
      author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' },
    }),
  )
  await fs.writeFile(path.join(dir, 'ui-src/package.json'), JSON.stringify({ dependencies: npm }))
  if (pom != null) {
    await fs.mkdir(path.join(dir, 'worker'), { recursive: true })
    await fs.writeFile(path.join(dir, 'worker/pom.xml'), pom)
  }
  const { detectProject } = await import('../src/project.mjs')
  return detectProject(dir)
}

test('version consistency passes when all @infinia/* agree', async () => {
  const dir = path.join(base, `v-ok-${Date.now()}`)
  const p = await projectWith(dir, {
    npm: { '@infinia/plugin-sdk': '^1.3.0', '@infinia/plugin-ui': '~1.3.0' },
  })
  assert.deepEqual(await checkToolchainVersionConsistency(await p), [])
})

test('version consistency is a no-op for workspace file: links', async () => {
  const dir = path.join(base, `v-link-${Date.now()}`)
  const p = await projectWith(dir, {
    npm: {
      '@infinia/plugin-sdk': 'file:../../../toolchain/sdk-ts',
      '@infinia/plugin-ui': 'file:../../../toolchain/ui',
    },
  })
  assert.deepEqual(await checkToolchainVersionConsistency(await p), [])
})

test('version consistency flags disagreeing @infinia/* versions', async () => {
  const dir = path.join(base, `v-mismatch-${Date.now()}`)
  const p = await projectWith(dir, {
    npm: { '@infinia/plugin-sdk': '^1.3.0', '@infinia/plugin-ui': '^1.2.0' },
  })
  const errors = await checkToolchainVersionConsistency(await p)
  assert.equal(errors.length, 1)
  assert.match(errors[0], /inconsistent/)
  assert.match(errors[0], /1\.3\.0/)
  assert.match(errors[0], /1\.2\.0/)
})

test('version consistency flags npm vs Maven SDK mismatch', async () => {
  const dir = path.join(base, `v-mvn-${Date.now()}`)
  const p = await projectWith(dir, {
    npm: { '@infinia/plugin-sdk': '^1.3.0' },
    pom: `<project>
      <properties><fengyu.plugin.sdk.version>1.2.0</fengyu.plugin.sdk.version></properties>
      <dependencies>
        <dependency>
          <groupId>fan.summer.fengyu.sdk</groupId>
          <artifactId>fengyu-plugin-sdk</artifactId>
          <version>\${fengyu.plugin.sdk.version}</version>
        </dependency>
      </dependencies>
    </project>`,
  })
  const errors = await checkToolchainVersionConsistency(await p)
  assert.equal(errors.length, 1)
  assert.match(errors[0], /Maven/)
  assert.match(errors[0], /1\.3\.0/)
  assert.match(errors[0], /1\.2\.0/)
})

test('version consistency passes when npm and Maven agree on the same X.Y.Z', async () => {
  const dir = path.join(base, `v-agree-${Date.now()}`)
  const p = await projectWith(dir, {
    npm: { '@infinia/plugin-sdk': '^1.3.0', '@infinia/plugin-ui': '^1.3.0' },
    pom: `<project>
      <properties><fengyu.plugin.sdk.version>1.3.0</fengyu.plugin.sdk.version></properties>
      <dependencies>
        <dependency>
          <groupId>fan.summer.fengyu.sdk</groupId>
          <artifactId>fengyu-plugin-sdk</artifactId>
          <version>\${fengyu.plugin.sdk.version}</version>
        </dependency>
      </dependencies>
    </project>`,
  })
  assert.deepEqual(await checkToolchainVersionConsistency(await p), [])
})
