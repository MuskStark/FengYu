import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { assertPackContents, uiStyleCssPath } from '../scripts/assert-pack-contents.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// The ui package's REAL exports map — the gate derives the published stylesheet
// path from it, so this fixture must stay anchored to the actual file (asserted
// below against toolchain/ui/package.json itself).
const uiPackageJson = {
  name: '@infinia/plugin-ui',
  exports: { './style.css': './dist/plugin-ui.css' },
}

test('uiStyleCssPath derives the published stylesheet from the ui exports map', () => {
  assert.equal(uiStyleCssPath(uiPackageJson), 'dist/plugin-ui.css')
  assert.throws(() => uiStyleCssPath({ exports: {} }), /style\.css/)
  assert.throws(() => uiStyleCssPath({ exports: { './style.css': 'dist/plugin-ui.css' } }), /\.\/dist/)
})

test('the repo ui package.json still maps ./style.css the way this suite assumes', async () => {
  const real = JSON.parse(await fs.readFile(path.resolve(__dirname, '../../ui/package.json'), 'utf8'))
  assert.equal(real.exports['./style.css'], './dist/plugin-ui.css')
})

function cliFiles() {
  return [
    { path: 'bin/fengyu.mjs' }, { path: 'src/cli.mjs' }, { path: 'src/generate.mjs' },
    { path: 'spec/manifest.schema.json' }, { path: 'templates/react-java/mvnw' },
    { path: 'templates/react-java/mvnw.cmd' },
    { path: 'templates/react-java/.mvn/settings.xml' },
    { path: 'templates/react-java/.mvn/wrapper/maven-wrapper.properties' },
    { path: 'templates/react-java/manifest.base.json.tpl' },
    { path: 'templates/react-java/ui-src/src/App.test.tsx' },
    { path: 'templates/react-java/worker/pom.xml.tpl' },
    { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}WorkerMain.java.tpl' },
    { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}Worker.java.tpl' },
    { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/contract/{{javaClassPrefix}}Contract.java.tpl' },
    { path: 'templates/react-java/worker/src/test/java/{{javaPackagePath}}/PluginDevMain.java.tpl' },
    { path: 'templates/react-codex/manifest.json.tpl' },
    { path: 'templates/react-python/worker/worker.py' },
    { path: 'templates/react-go/worker/main.go.tpl' },
  ]
}

test('accepts packages with their required published files', () => {
  assert.doesNotThrow(() => assertPackContents([
    { name: '@infinia/plugin-cli', files: cliFiles() },
    { name: '@infinia/plugin-dev', files: [{ path: 'dist/index.js' }, { path: 'dist/index.d.ts' }] },
    { name: '@infinia/plugin-sdk', files: [
      { path: 'dist/index.js' }, { path: 'dist/index.d.ts' },
      { path: 'dist/protocol.js' }, { path: 'dist/protocol.d.ts' },
    ] },
    { name: '@infinia/plugin-ui', files: [
      { path: 'dist/index.js' }, { path: 'dist/index.d.ts' }, { path: 'dist/plugin-ui.css' },
    ] },
  ], uiPackageJson))
})

test('the gate fails on the pre-fix stale paths (dist/style.css, App.test.ts)', () => {
  const staleUi = [{ path: 'dist/index.js' }, { path: 'dist/index.d.ts' }, { path: 'dist/style.css' }]
  assert.throws(
    () => assertPackContents([{ name: '@infinia/plugin-ui', files: staleUi }], uiPackageJson),
    /missing dist\/plugin-ui\.css/,
  )
  const staleCli = cliFiles().map((file) =>
    file.path === 'templates/react-java/ui-src/src/App.test.tsx'
      ? { path: 'templates/react-java/ui-src/src/App.test.ts' }
      : file)
  assert.throws(
    () => assertPackContents([{ name: '@infinia/plugin-cli', files: staleCli }], uiPackageJson),
    /missing templates\/react-java\/ui-src\/src\/App\.test\.tsx/,
  )
})

test('rejects missing or source-only files', () => {
  assert.throws(
    () => assertPackContents([{ name: '@infinia/plugin-sdk', files: [{ path: 'test/sdk.test.mjs' }] }], uiPackageJson),
    /missing|forbidden/,
  )
})

test('rejects package tests but permits generated-project test templates', () => {
  assert.doesNotThrow(() => assertPackContents([{ name: '@infinia/plugin-cli', files: cliFiles() }], uiPackageJson))
  assert.throws(
    () => assertPackContents([{ name: '@infinia/plugin-cli', files: [...cliFiles(), { path: 'test/cli.test.mjs' }] }], uiPackageJson),
    /forbidden/,
  )
})
