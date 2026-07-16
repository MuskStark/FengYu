import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { resolveCommand, runCommand } from '../src/commands.mjs'

let base
test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-cmd-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

async function withWrapper(root) {
  await fs.mkdir(path.join(root, '.mvn/wrapper'), { recursive: true })
  await fs.writeFile(path.join(root, 'mvnw'), '#!/bin/sh\nexec mvn "$@"\n', { mode: 0o755 })
  await fs.writeFile(path.join(root, 'mvnw.cmd'), '@echo off\r\nmvn %*')
  await fs.writeFile(path.join(root, '.mvn/wrapper/maven-wrapper.properties'), 'distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip\n')
}

test('resolves maven token to the wrapper script on linux', async () => {
  const root = path.join(base, 'linux')
  await withWrapper(root)
  const resolved = await resolveCommand(['maven', 'test'], root, { platform: 'linux' })
  assert.equal(resolved.command, path.join(root, 'mvnw'))
  assert.deepEqual(resolved.args, ['test'])
})

test('resolves maven token to mvnw.cmd on windows', async () => {
  const root = path.join(base, 'win')
  await withWrapper(root)
  const resolved = await resolveCommand(['maven', 'package'], root, { platform: 'win32' })
  assert.equal(resolved.command, path.join(root, 'mvnw.cmd'))
  assert.deepEqual(resolved.args, ['package'])
})

test('missing wrapper throws a precise error and never falls back to system maven', async () => {
  const root = path.join(base, 'no-wrapper')
  await fs.mkdir(root, { recursive: true })
  await assert.rejects(() => resolveCommand(['maven', 'test'], root, { platform: 'linux' }), /Maven Wrapper/)
})

test('non-maven commands are passed through unchanged', async () => {
  const resolved = await resolveCommand(['npm', 'ci'], base, { platform: 'linux' })
  assert.equal(resolved.command, 'npm')
  assert.deepEqual(resolved.args, ['ci'])
})

test('github token is mapped into the child environment for maven commands', async () => {
  const root = path.join(base, 'env')
  await withWrapper(root)
  const oldGithub = process.env.GITHUB_TOKEN
  const oldFengyu = process.env.FENGYU_GITHUB_TOKEN
  delete process.env.FENGYU_GITHUB_TOKEN
  process.env.GITHUB_TOKEN = 'ghp_test'
  try {
    const resolved = await resolveCommand(['maven', 'test'], root, { platform: 'linux' })
    assert.equal(resolved.env.FENGYU_GITHUB_TOKEN, 'ghp_test')
    assert.equal(resolved.env.GITHUB_ACTOR, 'fengyu-plugin-developer')
  } finally {
    if (oldGithub === undefined) delete process.env.GITHUB_TOKEN; else process.env.GITHUB_TOKEN = oldGithub
    if (oldFengyu === undefined) delete process.env.FENGYU_GITHUB_TOKEN; else process.env.FENGYU_GITHUB_TOKEN = oldFengyu
  }
})

test('runCommand remains a thin spawner that resolves on zero exit', async () => {
  const result = await runCommand('node', ['-e', 'process.exit(0)'])
  assert.equal(result.code, 0)
})
