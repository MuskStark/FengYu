import assert from 'node:assert/strict'
import { chmod, copyFile, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'

const repository = path.resolve(import.meta.dirname, '..')
const launcherSource = path.join(repository, 'distribution', 'web', 'run.sh')

async function fixture() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fengyu-launcher-'))
  const launcher = path.join(root, 'run.sh')
  const javaHome = path.join(root, 'jdk')
  const java = path.join(javaHome, 'bin', 'java')
  const captured = path.join(root, 'java-args.txt')
  await mkdir(path.dirname(java), { recursive: true })
  await copyFile(launcherSource, launcher)
  // The launcher's floor gate is the app's compile target (Java 25): the fixture java
  // reports exactly that line, so every test below also proves the gate accepts it.
  await writeFile(java, `#!/bin/sh
if [ "$1" = "-version" ]; then
  echo 'openjdk version "25.0.1"' >&2
  exit 0
fi
printf '%s\\n' "$@" > "$FAKE_JAVA_ARGS"
`)
  await chmod(launcher, 0o755)
  await chmod(java, 0o755)
  return { launcher, javaHome, captured }
}

function run({ launcher, javaHome, captured }, args) {
  return spawnSync('bash', [launcher, ...args], {
    encoding: 'utf8',
    env: { ...process.env, JAVA_HOME: javaHome, FAKE_JAVA_ARGS: captured },
  })
}

test('portable launcher generates one token when none is supplied', async () => {
  const f = await fixture()
  const result = run(f, ['--port=0'])
  assert.equal(result.status, 0, result.stderr)
  const args = (await readFile(f.captured, 'utf8')).trim().split('\n')
  assert.equal(args.filter(arg => arg.startsWith('--token=')).length, 1)
  assert.match(result.stderr, /Generated per-launch token/)
})

test('portable launcher preserves one explicit non-empty token', async () => {
  const f = await fixture()
  const result = run(f, ['--token=secret', '--port=0'])
  assert.equal(result.status, 0, result.stderr)
  const args = (await readFile(f.captured, 'utf8')).trim().split('\n')
  assert.equal(args.filter(arg => arg === '--token=secret').length, 1)
  assert.doesNotMatch(result.stderr, /Generated per-launch token/)
})

test('portable launcher rejects a JDK older than the compile floor', async () => {
  const f = await fixture()
  const oldJava = path.join(f.javaHome, 'bin', 'java')
  await writeFile(oldJava, `#!/bin/sh
if [ "$1" = "-version" ]; then
  echo 'openjdk version "21.0.1"' >&2
  exit 0
fi
exit 1
`)
  await chmod(oldJava, 0o755)
  const result = run(f, [])
  assert.equal(result.status, 1)
  assert.match(result.stderr, /Java 25 is required/)
})

for (const args of [
  ['--token'],
  ['--token='],
  ['--token=   '],
  ['--tokenized=value'],
  ['--token=first', '--token=second'],
]) {
  test(`portable launcher rejects malformed token arguments: ${JSON.stringify(args)}`, async () => {
    const f = await fixture()
    const result = run(f, args)
    assert.equal(result.status, 2)
    assert.match(result.stderr, /Invalid/)
  })
}

test('Windows launcher no longer uses token-prefix findstr detection', async () => {
  const batch = await readFile(path.join(repository, 'distribution', 'web', 'run.bat'), 'utf8')
  assert.doesNotMatch(batch, /findstr \/b "--token"/)
  assert.match(batch, /!ARG:~0,8!"=="--token="/)
  assert.match(batch, /--token may be supplied only once/)
})
