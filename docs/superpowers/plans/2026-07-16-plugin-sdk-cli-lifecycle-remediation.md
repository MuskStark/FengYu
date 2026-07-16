# Plugin SDK and CLI Lifecycle Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the reviewed SDK/CLI lifecycle gaps so a clean consumer can scaffold, develop, validate, package, publish, and install a Vue + Java FengYu plugin through the declared toolchain.

**Architecture:** Preserve the existing project model and manifest schema, but strengthen the boundaries between source paths, runtime paths, staged trees, and archives. Make package validation reusable at build and install time, make generated and published artifacts independently executable, and move release version resolution into a testable script shared by the workflow.

**Tech Stack:** Node.js 20 ESM, npm, Java 21, Maven Wrapper 3.9.11, Vue 3.5, TypeScript 5.9, Vite 7, Vitest 3, JUnit 5, GitHub Actions, JSON-RPC 2.0.

---

## File and Responsibility Map

### Scaffold correctness

- `plugin-cli/src/create.mjs` — render templates while preserving source file modes.
- `plugin-cli/templates/vue-java/fengyu.plugin.json.tpl` — declare the generated worker artifact relative to the plugin root.
- `plugin-cli/test/create.test.mjs` — verify executable wrapper mode and normalized generated configuration.

### Independently publishable SDK

- `plugin-sdk/typescript/package.json` — local TypeScript dependency and self-contained scripts.
- `plugin-sdk/typescript/package-lock.json` — lock the package-local compiler.
- `plugin-sdk/typescript/test/package.test.mjs` — assert scripts and isolated build behavior.

### Runtime and archive validation

- `plugin-cli/src/archive.mjs` — archive size/path inspection and safe entry reading.
- `plugin-cli/src/manifest.mjs` — staging and archive runtime validation, including worker JAR manifest checks.
- `plugin-cli/src/install.mjs` — call archive runtime validation before any fetch.
- `plugin-cli/test/archive.test.mjs` — archive size, absolute path, and entry-reading tests.
- `plugin-cli/test/build.test.mjs` — worker `Main-Class` and runtime resource layout tests.
- `plugin-cli/test/install.test.mjs` — missing/malformed backend rejection before fetch.

### Resource path typing

- `plugin-cli/src/config.mjs` — distinguish absolute source paths from archive-relative destinations.
- `plugin-cli/src/staging.mjs` — stage resources at the validated relative destination.
- `plugin-cli/test/config.test.mjs` — safe runtime destination validation.
- `plugin-cli/test/build.test.mjs` — final archive path assertions.

### Worker development lifecycle

- `plugin-cli/src/worker.mjs` — spawn readiness, request cleanup, and child termination.
- `plugin-cli/src/dev.mjs` — rebuilding state, queued changes, atomic replacement, and close-during-rebuild cleanup.
- `plugin-cli/test/worker.test.mjs` — spawn error and listener cleanup.
- `plugin-cli/test/dev.test.mjs` — rebuilding behavior and worker leak prevention.

### Release and final acceptance

- `plugin-cli/scripts/resolve-tooling-version.mjs` — normalize and verify release versions.
- `plugin-cli/test/release-version.test.mjs` — tag/manual/version mismatch coverage.
- `.github/workflows/plugin-tooling-release.yml` — use normalized versions and run the full publication gate.
- `.github/workflows/plugin-tooling.yml` — use deterministic installs and run host smoke after official package builds.
- `scripts/plugin-tooling-local-smoke.sh` — local tarball-based generated-project smoke without registry publication.
- `docs/en/plugins/*.md`, `docs/zh/plugins/*.md`, `plugin-cli/README.md` — keep invocation and validation behavior accurate.

---

### Task 1: Make the default generated project executable and path-correct

**Files:**
- Modify: `plugin-cli/src/create.mjs`
- Modify: `plugin-cli/templates/vue-java/fengyu.plugin.json.tpl`
- Modify: `plugin-cli/test/create.test.mjs`

- [ ] **Step 1: Add failing generated-wrapper and artifact-path tests**

Extend `plugin-cli/test/create.test.mjs` with a full-template assertion:

```js
import { loadBuildConfig } from '../src/config.mjs'

test('full scaffold preserves the Maven wrapper mode and worker artifact location', async () => {
  const root = path.join(base, `mode-${Date.now()}`)
  await createPlugin(root, 'com.example.demo', { install: false })

  if (process.platform !== 'win32') {
    const mode = (await fs.stat(path.join(root, 'mvnw'))).mode & 0o777
    assert.equal(mode, 0o755)
  }

  const config = await loadBuildConfig(root)
  assert.equal(
    config.worker.artifact,
    path.join(root, 'worker', 'target', 'Demo-worker.jar'),
  )
})
```

- [ ] **Step 2: Run the focused test and verify both reviewed failures**

Run:

```bash
cd plugin-cli
node --test --test-name-pattern 'wrapper mode|artifact location' test/create.test.mjs
```

Expected: FAIL because generated `mvnw` is mode `0644` and the artifact resolves to `<root>/target/Demo-worker.jar`.

- [ ] **Step 3: Preserve template file mode during rendering**

Change the file branch in `renderTemplate` so it copies source metadata after writing:

```js
} else {
  const sourceStat = await fs.stat(srcPath)
  const raw = await fs.readFile(srcPath, 'utf8')
  await fs.writeFile(destPath, applyPlaceholders(raw, replacements))
  await fs.chmod(destPath, sourceStat.mode & 0o777)
}
```

Do not add platform-specific chmod logic; Node ignores Unix executable semantics on Windows, while the generated `mvnw.cmd` remains the selected wrapper there.

- [ ] **Step 4: Correct the generated artifact path**

Change the worker declaration in `plugin-cli/templates/vue-java/fengyu.plugin.json.tpl`:

```json
"artifact": "worker/target/{{javaClassPrefix}}-worker.jar"
```

Keep `worker.root` as `worker`; commands run there, while artifact paths remain project-root-relative under the existing config contract.

- [ ] **Step 5: Run scaffold and config regression tests**

Run:

```bash
cd plugin-cli
node --test test/create.test.mjs test/config.test.mjs
```

Expected: all tests pass, including mode `0755` on Unix and the `worker/target` artifact assertion.

- [ ] **Step 6: Commit**

```bash
git add plugin-cli/src/create.mjs \
  plugin-cli/templates/vue-java/fengyu.plugin.json.tpl \
  plugin-cli/test/create.test.mjs
git commit -m "🐛 fix(plugin-cli): generate buildable worker scaffolds"
```

---

### Task 2: Make the TypeScript SDK package self-contained

**Files:**
- Modify: `plugin-sdk/typescript/package.json`
- Modify: `plugin-sdk/typescript/package-lock.json`
- Create: `plugin-sdk/typescript/test/package.test.mjs`
- Modify: `.github/workflows/plugin-tooling.yml`

- [ ] **Step 1: Add a failing package-boundary test**

Create `plugin-sdk/typescript/test/package.test.mjs`:

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'

test('build uses the package-local TypeScript compiler', async () => {
  const pkg = JSON.parse(await fs.readFile(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.scripts.build, 'tsc -p tsconfig.json')
  assert.match(pkg.devDependencies.typescript, /^\^?5\./)
  assert.doesNotMatch(JSON.stringify(pkg.scripts), /frontend\/node_modules/)
})
```

- [ ] **Step 2: Run the package test and verify it fails**

Run:

```bash
cd plugin-sdk/typescript
node --test test/package.test.mjs
```

Expected: FAIL because the build script references `../../frontend/node_modules/.bin/tsc` and TypeScript is undeclared.

- [ ] **Step 3: Add the local compiler and normalize scripts**

Update `plugin-sdk/typescript/package.json`:

```json
"scripts": {
  "build": "tsc -p tsconfig.json",
  "test": "npm run build && node --test test/*.test.mjs",
  "prepack": "npm test"
},
"devDependencies": {
  "typescript": "^5.9.2"
}
```

Use `npm install --save-dev typescript@^5.9.2` inside `plugin-sdk/typescript` to update the lockfile mechanically.

- [ ] **Step 4: Add an isolated-directory verification to CI**

After the normal SDK test step in `.github/workflows/plugin-tooling.yml`, add:

```yaml
- name: Verify TypeScript SDK in isolation
  run: |
    SDK_TMP="$(mktemp -d)"
    cp -R plugin-sdk/typescript/. "$SDK_TMP/"
    rm -rf "$SDK_TMP/node_modules"
    cd "$SDK_TMP"
    npm ci
    npm test
```

This test must not copy `frontend/node_modules` or the repository root `node_modules`.

- [ ] **Step 5: Run package tests from both repository and isolated copies**

Run:

```bash
cd plugin-sdk/typescript
npm ci
npm test
SDK_TMP="$(mktemp -d)"
cp -R . "$SDK_TMP/"
rm -rf "$SDK_TMP/node_modules"
cd "$SDK_TMP" && npm ci && npm test
```

Expected: both runs pass without accessing `frontend/node_modules`.

- [ ] **Step 6: Commit**

```bash
git add plugin-sdk/typescript/package.json \
  plugin-sdk/typescript/package-lock.json \
  plugin-sdk/typescript/test/package.test.mjs \
  .github/workflows/plugin-tooling.yml
git commit -m "🐛 fix(plugin-sdk): make typescript package self-contained"
```

---

### Task 3: Correct runtime resource destination handling

**Files:**
- Modify: `plugin-cli/src/config.mjs`
- Modify: `plugin-cli/src/staging.mjs`
- Modify: `plugin-cli/test/config.test.mjs`
- Modify: `plugin-cli/test/build.test.mjs`

- [ ] **Step 1: Add failing safe-runtime-path tests**

Add these cases to `plugin-cli/test/config.test.mjs`:

```js
test('package resource destinations remain archive-relative', async () => {
  const root = await makeProject({
    package: { resources: [{ from: 'assets', to: 'runtime-assets' }] },
  })
  await fs.mkdir(path.join(root, 'assets'), { recursive: true })
  const cfg = await loadBuildConfig(root)
  assert.equal(cfg.package.resources[0].to, 'runtime-assets')
})

for (const unsafe of ['/absolute', '../escape', 'a/../../escape', 'C:\\escape', 'a\\b']) {
  test(`rejects unsafe runtime resource destination ${unsafe}`, async () => {
    const root = await makeProject({
      package: { resources: [{ from: 'assets', to: unsafe }] },
    })
    await assert.rejects(() => loadBuildConfig(root), /package\.resources\[0\]\.to/)
  })
}
```

Adapt the existing `makeProject` helper rather than duplicating its setup.

- [ ] **Step 2: Add a failing staging-layout assertion**

Extend the declared package fixture in `plugin-cli/test/build.test.mjs` with:

```js
package: {
  outputDirectory: 'dist-package',
  resources: [{ from: 'assets', to: 'runtime-assets' }],
}
```

Create `assets/example.txt`, build the package, inspect its entries, and assert:

```js
assert.ok(names.has('runtime-assets/example.txt'))
assert.equal([...names].some((name) => name.includes(root.replace(/^\//, ''))), false)
```

- [ ] **Step 3: Run config and build tests to verify failure**

Run:

```bash
cd plugin-cli
node --test --test-name-pattern 'resource destination|runtime-assets' \
  test/config.test.mjs test/build.test.mjs
```

Expected: FAIL because `to` is an absolute project path and staging embeds the source path prefix.

- [ ] **Step 4: Add a dedicated runtime-relative path validator**

In `plugin-cli/src/config.mjs`, add:

```js
function requireRuntimePath(value, field) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`${field} must be a non-empty relative runtime path`)
  }
  if (value.includes('\\') || path.posix.isAbsolute(value) || /^[A-Za-z]:/.test(value)) {
    throw new Error(`${field} "${value}" must be a POSIX relative runtime path`)
  }
  const normalized = path.posix.normalize(value)
  if (normalized === '.' || normalized === '..' || normalized.startsWith('../')) {
    throw new Error(`${field} "${value}" escapes package root`)
  }
  if (normalized === 'manifest.json') {
    throw new Error(`${field} must not overwrite manifest.json`)
  }
  return normalized
}
```

Use it only for `package.resources[].to`; continue using `resolveInside` for `from`.

- [ ] **Step 5: Stage resources beneath the validated relative path**

Keep `staging.mjs` simple:

```js
for (const { from, to } of cfg.package.resources) {
  const dest = path.join(staging, ...to.split('/'))
  // existing lstat/copy logic
}
```

Because `to` is validated and remains relative, it cannot introduce the project source prefix.

- [ ] **Step 6: Run config, staging, and archive tests**

Run:

```bash
cd plugin-cli
node --test test/config.test.mjs test/build.test.mjs
```

Expected: safe relative resources appear exactly under their declared archive paths and all unsafe destinations fail during config loading.

- [ ] **Step 7: Commit**

```bash
git add plugin-cli/src/config.mjs plugin-cli/src/staging.mjs \
  plugin-cli/test/config.test.mjs plugin-cli/test/build.test.mjs
git commit -m "🐛 fix(plugin-cli): preserve runtime resource paths"
```

---

### Task 4: Centralize archive validation and enforce worker startup metadata

**Files:**
- Modify: `plugin-cli/src/archive.mjs`
- Modify: `plugin-cli/src/manifest.mjs`
- Modify: `plugin-cli/src/install.mjs`
- Modify: `plugin-cli/test/archive.test.mjs`
- Modify: `plugin-cli/test/build.test.mjs`
- Modify: `plugin-cli/test/install.test.mjs`

- [ ] **Step 1: Add failing archive-size and absolute-path tests**

Extend `plugin-cli/test/archive.test.mjs`:

```js
test('rejects an archive file larger than 100 MB', async () => {
  const file = path.join(base, 'oversized.fyp')
  await fs.writeFile(file, Buffer.alloc(MAX_PACKAGE_BYTES + 1))
  await assert.rejects(() => inspectArchive(file), /package exceeds 100 MB/)
})

test('rejects absolute archive entry paths', async () => {
  const file = await writeZip([{ name: '/absolute.txt', data: 'x' }])
  await assert.rejects(() => inspectArchive(file), /unsafe archive path/)
})
```

Import `MAX_PACKAGE_BYTES` from `archive.mjs`.

- [ ] **Step 2: Add failing worker `Main-Class` validation tests**

Refactor the existing fake JAR helper in `plugin-cli/test/build.test.mjs` to accept separate class-entry and manifest-main-class values:

```js
await makeFakeJar(jar, {
  classEntry: 'com.example.DeclaredWorkerMain',
  manifestMainClass: 'com.example.WrongMain',
})
```

Build the declared project and assert rejection:

```js
await assert.rejects(
  () => buildPlugin(root, { run }),
  /worker JAR Main-Class .* does not match com\.example\.DeclaredWorkerMain/,
)
```

- [ ] **Step 3: Add failing offline-install backend tests**

Add to `plugin-cli/test/install.test.mjs`:

```js
test('rejects a backend package missing worker.jar before fetch', async () => {
  let fetched = 0
  const file = await writeFyp('missing-worker', [
    { name: 'manifest.json', data: backendManifest },
    { name: 'ui/index.html', data: '<html></html>' },
  ])
  await assert.rejects(
    () => installPlugin(file, { fetchImpl: async () => { fetched++; return new Response() } }),
    /backend\/worker\.jar/,
  )
  assert.equal(fetched, 0)
})
```

Add a second case with `backend/worker.jar` present but a non-zip payload and assert zero fetch calls.

- [ ] **Step 4: Run focused archive/install/build tests and verify failure**

Run:

```bash
cd plugin-cli
node --test --test-name-pattern '100 MB|absolute archive|Main-Class|missing worker|malformed worker' \
  test/archive.test.mjs test/build.test.mjs test/install.test.mjs
```

Expected: all new cases fail against the current partial validation.

- [ ] **Step 5: Add safe archive entry reading and package-size enforcement**

In `archive.mjs`:

1. Before opening a path input, call `fs.stat` and reject sizes above `MAX_PACKAGE_BYTES`.
2. Reject Buffer inputs whose byte length exceeds the same limit.
3. Reject entry names beginning with `/`, `\\`, or a drive-letter prefix before normalization.
4. Export a single safe reader:

```js
export async function readArchiveEntry(file, expectedName, { maxBytes = 1024 * 1024 } = {}) {
  // open lazily, find exactly expectedName, stream at most maxBytes,
  // close on every resolve/reject path, and reject missing entries.
}
```

Use this helper for `manifest.json` and `META-INF/MANIFEST.MF`; remove the duplicate private reader from `install.mjs`.

- [ ] **Step 6: Parse and validate the JAR Main-Class**

Add focused helpers in `manifest.mjs`:

```js
function parseJarManifest(text) {
  const unfolded = text.replace(/\r?\n ([^\r\n]*)/g, '$1')
  const values = new Map()
  for (const line of unfolded.split(/\r?\n/)) {
    const index = line.indexOf(':')
    if (index > 0) values.set(line.slice(0, index), line.slice(index + 1).trim())
  }
  return values
}

async function validateWorkerJar(jar, expectedMainClass) {
  const { entries } = await inspectArchive(jar)
  const names = new Set(entries.map((entry) => entry.name))
  const manifestText = await readArchiveEntry(jar, 'META-INF/MANIFEST.MF')
  const actualMainClass = parseJarManifest(manifestText).get('Main-Class')
  const errors = []
  if (actualMainClass !== expectedMainClass) {
    errors.push(`worker JAR Main-Class ${actualMainClass ?? '<missing>'} does not match ${expectedMainClass}`)
  }
  const classEntry = expectedMainClass.replace(/\./g, '/') + '.class'
  if (!names.has(classEntry)) errors.push(`worker JAR is missing class entry ${classEntry}`)
  return errors
}
```

Use it from `validateRuntimeTree` for declared builds.

- [ ] **Step 7: Add archive-level runtime validation for installation**

Export from `manifest.mjs`:

```js
export async function validatePluginArchive(file) {
  const { entries } = await inspectArchive(file)
  const names = new Set(entries.filter((entry) => !entry.isDirectory).map((entry) => entry.name))
  const manifest = JSON.parse(await readArchiveEntry(file, 'manifest.json'))
  const errors = validateManifestObject(manifest)

  if (manifest.ui?.entry && !names.has(manifest.ui.entry)) {
    errors.push(`package is missing UI entry: ${manifest.ui.entry}`)
  }
  if (manifest.backend) {
    if (manifest.backend.protocol !== 'json-rpc-2.0') errors.push('unsupported backend protocol')
    if (manifest.backend.command !== 'java -jar backend/worker.jar') {
      errors.push('backend.command must be java -jar backend/worker.jar')
    }
    if (!names.has('backend/worker.jar')) errors.push('package is missing backend/worker.jar')
    else {
      try { await inspectArchiveEntryAsZip(file, 'backend/worker.jar') }
      catch (error) { errors.push(`worker JAR inspection failed: ${error.message}`) }
    }
  }
  return { manifest, errors }
}
```

Implement `inspectArchiveEntryAsZip` by reading the bounded worker entry into a Buffer and passing it to `inspectArchive`. Do not execute the worker. Archive install validation cannot infer a configured `mainClass`, so it checks structural JAR validity; declared build validation remains responsible for exact `Main-Class` equality.

- [ ] **Step 8: Make install validate before constructing FormData or fetching**

Replace the current manifest/UI-only code in `install.mjs`:

```js
const { manifest, errors } = await validatePluginArchive(file)
if (errors.length) throw new Error(errors.join('\n'))
```

Only then read the full archive into the upload `Blob` and call `fetchImpl`.

- [ ] **Step 9: Run the full CLI validation suite**

Run:

```bash
cd plugin-cli
npm test
```

Expected: all archive, build, install, official-project, and legacy compatibility tests pass with zero fetch calls for locally invalid packages.

- [ ] **Step 10: Commit**

```bash
git add plugin-cli/src/archive.mjs plugin-cli/src/manifest.mjs \
  plugin-cli/src/install.mjs plugin-cli/test/archive.test.mjs \
  plugin-cli/test/build.test.mjs plugin-cli/test/install.test.mjs
git commit -m "🐛 fix(plugin-cli): validate complete plugin archives offline"
```

---

### Task 5: Make worker dev restart and shutdown lifecycle-safe

**Files:**
- Modify: `plugin-cli/src/worker.mjs`
- Modify: `plugin-cli/src/dev.mjs`
- Modify: `plugin-cli/test/worker.test.mjs`
- Modify: `plugin-cli/test/dev.test.mjs`

- [ ] **Step 1: Add failing worker spawn and cleanup tests**

Add to `plugin-cli/test/worker.test.mjs`:

```js
test('startWorker rejects when the executable cannot spawn', async () => {
  await assert.rejects(
    () => startWorker({ jar: 'missing.jar', java: path.join(os.tmpdir(), 'missing-java') }),
    /ENOENT|spawn/,
  )
})

test('timeout removes the abort listener', async () => {
  const client = await startFixture()
  const controller = new AbortController()
  let removes = 0
  const remove = controller.signal.removeEventListener.bind(controller.signal)
  controller.signal.removeEventListener = (...args) => { removes++; return remove(...args) }
  await assert.rejects(
    () => client.invoke('never', {}, { signal: controller.signal, timeoutMs: 5 }),
    /timed out/,
  )
  assert.equal(removes, 1)
  await client.close()
})
```

Extend the fixture with a `never` method that intentionally does not reply.

- [ ] **Step 2: Add failing rebuild-state and close-during-rebuild tests**

Use deferred promises in `plugin-cli/test/dev.test.mjs` to control the build and replacement worker:

```js
test('declared dev rejects RPC while rebuilding and closes the replacement on shutdown', async () => {
  const buildStarted = deferred()
  const releaseBuild = deferred()
  const clients = []
  const server = await dev(root, port, {
    run: async () => { buildStarted.resolve(); await releaseBuild.promise },
    startWorkerImpl: async () => {
      const client = fakeWorkerClient()
      clients.push(client)
      return client
    },
    open: false,
  })

  await touchJavaSource(root)
  await buildStarted.promise
  const response = await postRpc(port, 'hello', {})
  assert.match(response.error, /worker rebuilding/)

  const closing = server.close()
  releaseBuild.resolve()
  await closing
  assert.equal(clients.every((client) => client.closeCalls === 1), true)
})
```

Add a separate test where a second file change occurs during the first build and assert exactly one follow-up rebuild.

- [ ] **Step 3: Run focused dev tests and verify failure**

Run:

```bash
cd plugin-cli
node --test --test-name-pattern 'cannot spawn|abort listener|while rebuilding|follow-up rebuild' \
  test/worker.test.mjs test/dev.test.mjs
```

Expected: FAIL because start readiness is not awaited, timeout listeners leak, RPC still reaches the old worker, and shutdown does not await replacement creation.

- [ ] **Step 4: Centralize pending-request cleanup in `worker.mjs`**

Add:

```js
function takePending(id) {
  const entry = pending.get(id)
  if (!entry) return undefined
  pending.delete(id)
  clearTimeout(entry.timer)
  if (entry.signal && entry.abort) entry.signal.removeEventListener('abort', entry.abort)
  return entry
}
```

Use it for response, timeout, abort, write error, child exit, and close paths. `rejectAll` must call the same cleanup behavior.

- [ ] **Step 5: Wait for process spawn before returning a client**

Immediately after `spawn`, create a readiness promise:

```js
await new Promise((resolve, reject) => {
  const onSpawn = () => { cleanup(); resolve() }
  const onError = (error) => { cleanup(); reject(error) }
  const onExit = (code, signal) => { cleanup(); reject(new Error(`worker exited before spawn readiness (code=${code}, signal=${signal})`)) }
  const cleanup = () => {
    child.removeListener('spawn', onSpawn)
    child.removeListener('error', onError)
    child.removeListener('exit', onExit)
  }
  child.once('spawn', onSpawn)
  child.once('error', onError)
  child.once('exit', onExit)
})
```

After readiness, retain an `error` listener that marks the client exited and rejects pending requests instead of allowing an unhandled event.

- [ ] **Step 6: Serialize rebuilds with rebuilding and dirty state**

In `devDeclaredWorker`, maintain:

```js
let workerClient
let rebuilding = null
let dirty = false
let closing = false
```

The watcher callback sets `dirty = true` and calls `scheduleRebuild()`. `scheduleRebuild` runs while dirty and not closing:

```js
async function scheduleRebuild() {
  if (rebuilding || closing) return
  rebuilding = (async () => {
    while (dirty && !closing) {
      dirty = false
      const next = await buildAndStartReplacement()
      const previous = workerClient
      workerClient = next
      await previous?.close().catch(() => {})
    }
  })().finally(() => { rebuilding = null })
  await rebuilding
}
```

The RPC bridge must check `rebuilding` before accessing `workerClient` and reject with `worker rebuilding`.

- [ ] **Step 7: Make close await and dispose every possible worker**

Implement close in this order:

```js
closing = true
dirty = false
await watcher.close?.().catch(() => {})
await rebuilding?.catch(() => {})
await workerClient?.close().catch(() => {})
await stopServer(server)
```

If a replacement starts after `closing` becomes true, close it immediately instead of assigning it to `workerClient`.

- [ ] **Step 8: Run worker and dev suites**

Run:

```bash
cd plugin-cli
node --test test/worker.test.mjs test/dev.test.mjs
```

Expected: spawn failures reject normally, every terminal request path removes listeners, rebuilding returns the explicit error, queued changes rebuild once more, and every created client closes exactly once.

- [ ] **Step 9: Commit**

```bash
git add plugin-cli/src/worker.mjs plugin-cli/src/dev.mjs \
  plugin-cli/test/worker.test.mjs plugin-cli/test/dev.test.mjs
git commit -m "🐛 fix(plugin-cli): make worker dev lifecycle atomic"
```

---

### Task 6: Normalize release versions and make publication gates executable

**Files:**
- Create: `plugin-cli/scripts/resolve-tooling-version.mjs`
- Create: `plugin-cli/test/release-version.test.mjs`
- Modify: `plugin-cli/package.json`
- Modify: `.github/workflows/plugin-tooling-release.yml`

- [ ] **Step 1: Write failing version-resolution tests**

Create `plugin-cli/test/release-version.test.mjs`:

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveToolingVersion } from '../scripts/resolve-tooling-version.mjs'

test('resolves a tooling tag to a semantic version', () => {
  assert.equal(resolveToolingVersion({ refName: 'plugin-tooling-v1.0.0' }), '1.0.0')
})

test('manual input takes precedence', () => {
  assert.equal(resolveToolingVersion({ inputVersion: '1.0.1', refName: 'plugin-tooling-v1.0.0' }), '1.0.1')
})

test('rejects unrelated or malformed tags', () => {
  assert.throws(() => resolveToolingVersion({ refName: 'v1.0.0' }), /plugin-tooling-v/)
  assert.throws(() => resolveToolingVersion({ refName: 'plugin-tooling-vlatest' }), /semantic version/)
})
```

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
cd plugin-cli
node --test test/release-version.test.mjs
```

Expected: FAIL because the resolver module does not exist.

- [ ] **Step 3: Implement a testable version resolver and repository checker**

Create `plugin-cli/scripts/resolve-tooling-version.mjs` exporting:

```js
export function resolveToolingVersion({ inputVersion = '', refName = '' }) {
  const value = inputVersion || (
    refName.startsWith('plugin-tooling-v')
      ? refName.slice('plugin-tooling-v'.length)
      : (() => { throw new Error('release tag must start with plugin-tooling-v') })()
  )
  if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(value)) {
    throw new Error(`tooling version is not semantic versioning: ${value}`)
  }
  return value
}
```

When run as a CLI, accept `--input`, `--ref`, and `--github-output`. Read and compare:

- `plugin-cli/package.json`
- `plugin-sdk/typescript/package.json`
- `plugin-ui/vue/package.json`
- `FengYu-Plugin-Sdk/pom.xml`

Fail with a list of mismatched files. Write `version=<normalized>` to `$GITHUB_OUTPUT` only after all four match.

- [ ] **Step 4: Add the resolver test to the CLI package suite**

No glob change is needed because `npm test` already runs `test/*.test.mjs`. Add a convenience script:

```json
"verify-version": "node scripts/resolve-tooling-version.mjs"
```

- [ ] **Step 5: Add a `resolve-version` job to the release workflow**

At the start of `.github/workflows/plugin-tooling-release.yml`, add a job with output `version`:

```yaml
resolve-version:
  runs-on: ubuntu-latest
  outputs:
    version: ${{ steps.version.outputs.version }}
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-node@v4
      with: { node-version: 20 }
    - id: version
      env:
        INPUT_VERSION: ${{ inputs.version }}
        REF_NAME: ${{ github.ref_name }}
      run: node plugin-cli/scripts/resolve-tooling-version.mjs \
        --input "$INPUT_VERSION" --ref "$REF_NAME" --github-output "$GITHUB_OUTPUT"
```

Make `verify` depend on `resolve-version`; make all other jobs consume `${{ needs.resolve-version.outputs.version }}` through their dependency chain.

Use explicit dependencies so the version output is available in every job:

```yaml
verify:
  needs: resolve-version
publish-java:
  needs: [resolve-version, verify]
publish-npm:
  needs: [resolve-version, publish-java]
consumer-smoke:
  needs: [resolve-version, publish-npm]
```

- [ ] **Step 6: Fix clean-consumer CLI invocation**

Replace both consumer commands with explicit package-qualified invocations:

```yaml
npx --yes @fengyu/plugin-cli@$VERSION plugin create demo --id com.example.demo
cd demo
npx --yes @fengyu/plugin-cli@$VERSION plugin build .
```

Do not use bare `fengyu` unless the workflow first installs the CLI into that temp project.

- [ ] **Step 7: Run resolver tests and workflow syntax checks**

Run:

```bash
cd plugin-cli
node --test test/release-version.test.mjs
node scripts/resolve-tooling-version.mjs --ref plugin-tooling-v1.0.0
cd ..
python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/plugin-tooling-release.yml"))'
```

If PyYAML is unavailable, use the repository's existing workflow linter or `ruby -e 'require "yaml"; YAML.load_file(ARGV[0])' .github/workflows/plugin-tooling-release.yml`. Expected: version `1.0.0`, no mismatches, and valid YAML.

- [ ] **Step 8: Commit**

```bash
git add plugin-cli/scripts/resolve-tooling-version.mjs \
  plugin-cli/test/release-version.test.mjs plugin-cli/package.json \
  .github/workflows/plugin-tooling-release.yml
git commit -m "🐛 fix(plugin-tooling): normalize release consumer versions"
```

---

### Task 7: Add local clean-consumer smoke and restore the full release verification gate

**Files:**
- Create: `scripts/plugin-tooling-local-smoke.sh`
- Modify: `.github/workflows/plugin-tooling.yml`
- Modify: `.github/workflows/plugin-tooling-release.yml`
- Modify: `plugin-cli/README.md`
- Modify: `docs/en/plugins/getting-started.md`
- Modify: `docs/zh/plugins/getting-started.md`
- Modify: `docs/en/plugins/build-deploy.md`
- Modify: `docs/zh/plugins/build-deploy.md`

- [ ] **Step 1: Add a failing local clean-consumer smoke script**

Create `scripts/plugin-tooling-local-smoke.sh` with strict mode and cleanup:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export FENGYU_GITHUB_TOKEN="local-smoke-placeholder"
export GITHUB_ACTOR="fengyu-local-smoke"

cd "$ROOT"
./mvnw -f FengYu-Plugin-Sdk/pom.xml install -DskipTests

cd "$ROOT/plugin-sdk/typescript"
npm ci
SDK_TGZ="$(npm pack --silent --pack-destination "$WORK")"

cd "$ROOT/plugin-ui/vue"
npm ci
UI_TGZ="$(npm pack --silent --pack-destination "$WORK")"

cd "$ROOT/plugin-cli"
npm ci
CLI_TGZ="$(npm pack --silent --pack-destination "$WORK")"

cd "$WORK"
npx --yes "./$CLI_TGZ" plugin create demo --id com.example.demo --no-install
cd demo/ui-src
npm install "$WORK/$SDK_TGZ" "$WORK/$UI_TGZ"
npm test
npm run build

cd "$WORK/demo"
test -x mvnw
./mvnw -f worker/pom.xml test

npx --yes "./$CLI_TGZ" plugin build .
test -f dist-package/com.example.demo-1.0.0.fyp
unzip -Z1 dist-package/com.example.demo-1.0.0.fyp | sort > package-entries.txt
grep -qx 'manifest.json' package-entries.txt
grep -qx 'backend/worker.jar' package-entries.txt
grep -qx 'ui/index.html' package-entries.txt
if grep -Eq '(^|/)(src|node_modules|target|\.git)(/|$)' package-entries.txt; then
  echo "FAIL: source/build files leaked into generated package" >&2
  exit 1
fi
```

`npm install "$WORK/$SDK_TGZ" "$WORK/$UI_TGZ"` updates the generated UI lockfile to the local tarballs, so the later declared `npm ci` remains deterministic. The placeholder GitHub token is never sent because the Java SDK was installed into the local Maven repository first.

- [ ] **Step 2: Run the script and verify the current implementation fails**

Run:

```bash
scripts/plugin-tooling-local-smoke.sh
```

Expected before Tasks 1–2: FAIL on wrapper execution or SDK packaging. After Tasks 1–2: proceed through the generated worker and package build.

- [ ] **Step 3: Add local smoke to normal tooling CI**

In `.github/workflows/plugin-tooling.yml`, add a job after the package and official plugin jobs:

```yaml
local-consumer-smoke:
  needs: [build, official-plugins]
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-node@v4
      with: { node-version: 20, cache: npm }
    - uses: actions/setup-java@v5
      with: { java-version: '21', distribution: temurin, cache: maven }
    - run: scripts/plugin-tooling-local-smoke.sh
```

- [ ] **Step 4: Restore the original full verification gate in release CI**

Extend the release `verify` job to run:

```yaml
- run: npm pack --dry-run --json --workspace=false
  working-directory: plugin-cli
- run: npm pack --dry-run --json --workspace=false
  working-directory: plugin-sdk/typescript
- run: npm pack --dry-run --json --workspace=false
  working-directory: plugin-ui/vue
- run: ./mvnw -pl FengYu -am package -DskipTests
- run: scripts/e2e-smoke.sh
- run: npm ci && npm run docs:build
```

The final command runs at repository root. Keep publication jobs dependent on the completed `verify` job.

- [ ] **Step 5: Make package-content assertions explicit**

Parse each `npm pack --dry-run --json` result and assert:

- CLI contains `bin/`, `src/`, `spec/`, and both templates;
- browser SDK contains `dist/index.js` and `dist/index.d.ts`;
- UI kit contains `dist/index.js`, `dist/index.d.ts`, and `dist/style.css`;
- none contain `test/`, source fixtures, `.env`, or `.npmrc`.

Put the reusable assertion code in `plugin-cli/scripts/assert-pack-contents.mjs` and add unit tests under `plugin-cli/test/pack-contents.test.mjs` rather than embedding JSON parsing in YAML.

- [ ] **Step 6: Correct lifecycle documentation commands**

Replace ambiguous sequences such as:

```bash
npx @fengyu/plugin-cli plugin create my-plugin --id com.example.my-plugin
npx fengyu plugin build .
```

with one consistent form:

```bash
npx --yes @fengyu/plugin-cli@1.0.0 plugin create my-plugin --id com.example.my-plugin
cd my-plugin
npx --yes @fengyu/plugin-cli@1.0.0 plugin dev .
npx --yes @fengyu/plugin-cli@1.0.0 plugin build .
npx --yes @fengyu/plugin-cli@1.0.0 plugin install \
  dist-package/com.example.my-plugin-1.0.0.fyp \
  --host http://127.0.0.1:24056
```

Document that backend packages are structurally validated before upload and that `package.resources[].to` is an archive-relative POSIX path.

- [ ] **Step 7: Run CI-equivalent local verification**

Run:

```bash
scripts/plugin-tooling-local-smoke.sh
cd plugin-cli && npm test && npm pack --dry-run
cd ../plugin-sdk/typescript && npm ci && npm test && npm pack --dry-run
cd ../../plugin-ui/vue && npm ci && npm run typecheck && npm test && npm run build && npm pack --dry-run
```

Expected: the generated local consumer builds through its wrapper, package contents are correct, and all npm suites pass.

- [ ] **Step 8: Commit**

```bash
git add scripts/plugin-tooling-local-smoke.sh \
  plugin-cli/scripts/assert-pack-contents.mjs \
  plugin-cli/test/pack-contents.test.mjs \
  .github/workflows/plugin-tooling.yml \
  .github/workflows/plugin-tooling-release.yml \
  plugin-cli/README.md docs/en/plugins/getting-started.md \
  docs/zh/plugins/getting-started.md docs/en/plugins/build-deploy.md \
  docs/zh/plugins/build-deploy.md
git commit -m "🧪 test(plugin-tooling): verify clean consumer lifecycle"
```

---

### Task 8: Run the complete remediation acceptance suite

**Files:**
- Modify only if verification exposes a defect in files already covered by Tasks 1–7.

- [ ] **Step 1: Verify no unplanned packaging path returned**

Run:

```bash
rg -n "build-packages\.sh|OfficialPlugins/packages" --glob '!docs/superpowers/**' .
```

Expected: no runtime, CI, script, or documentation references.

- [ ] **Step 2: Run all Java and Node tests**

Run:

```bash
cd plugin-sdk/typescript && npm ci && npm test
cd ../../plugin-ui/vue && npm ci && npm run typecheck && npm test && npm run build
cd ../../plugin-cli && npm ci && npm test
cd .. && ./mvnw -f FengYu-Plugin-Sdk/pom.xml test
./mvnw -pl FengYu -am test
```

Expected: every command exits 0 with zero failures.

- [ ] **Step 3: Build every official plugin exclusively through the CLI**

Run:

```bash
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-markdown
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-excel
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-email
```

Expected: three `.fyp` files under their plugin-local `dist-package/` directories, each containing `manifest.json`, `ui/**`, and `backend/worker.jar` only.

- [ ] **Step 4: Run local and host end-to-end smoke**

Run:

```bash
scripts/plugin-tooling-local-smoke.sh
./mvnw -pl FengYu -am package -DskipTests
scripts/e2e-smoke.sh
```

Expected: the generated consumer builds and the host smoke prints PASS for Markdown, Excel, and Email worker flows.

- [ ] **Step 5: Verify npm package contents and docs**

Run:

```bash
cd plugin-sdk/typescript && npm pack --dry-run --json --workspace=false
cd ../../plugin-ui/vue && npm pack --dry-run --json --workspace=false
cd ../../plugin-cli && npm pack --dry-run --json --workspace=false
cd .. && npm run docs:build
```

Expected: required dist/template/schema files are present, source-only test assets are absent, and VitePress reports no dead links.

- [ ] **Step 6: Verify repository hygiene**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors introduced by remediation files; status contains only the intended task changes and generated ignored artifacts.

- [ ] **Step 7: Record published-consumer verification requirement**

Do not mark the tooling release complete merely because local tarball smoke passes. Trigger either:

```text
plugin-tooling-v1.0.0
```

or a manual release dispatch with version `1.0.0`, then require the `consumer-smoke` job to pass using registry packages and GitHub Packages without `file:` dependencies.

- [ ] **Step 8: Commit any final verification-only adjustments**

If no adjustments were needed, do not create an empty commit. If verification exposes a defect, return to the task that owns that behavior, add a failing regression test there, amend that task's implementation, and rerun Steps 1–6 before committing the exact owning test and implementation files.

---

## Final Review Checklist

- [ ] Generated `mvnw` is executable on Unix and `mvnw.cmd` remains usable on Windows.
- [ ] Generated worker artifact resolves to `worker/target/<finalName>.jar`.
- [ ] TypeScript SDK builds with only its own package files and dependencies.
- [ ] Package resource destinations remain safe archive-relative paths.
- [ ] Archive inspection enforces 100 MB compressed/package and 300 MB expanded limits.
- [ ] Declared builds reject missing or mismatched worker `Main-Class` metadata.
- [ ] Offline install rejects missing or malformed backend workers before fetch.
- [ ] Worker dev rejects RPC while rebuilding, queues one follow-up rebuild, and leaks no child processes.
- [ ] Release tags and manual inputs normalize to one verified semantic version.
- [ ] Consumer smoke invokes the scoped CLI explicitly for every command.
- [ ] Release verification runs package checks, official builds, host smoke, and docs before publishing.
- [ ] Local tarball smoke passes.
- [ ] Published registry consumer smoke passes without `file:` dependencies.
