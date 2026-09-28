import test from 'node:test'
import assert from 'node:assert/strict'
import { assertPackContents } from '../scripts/assert-pack-contents.mjs'

test('accepts packages with their required published files', () => {
  assert.doesNotThrow(() => assertPackContents([
    { name: '@infinia/plugin-cli', files: [
      { path: 'bin/fengyu.mjs' }, { path: 'src/cli.mjs' }, { path: 'src/generate.mjs' },
      { path: 'spec/manifest.schema.json' }, { path: 'templates/react-java/mvnw' },
      { path: 'templates/react-java/mvnw.cmd' },
      { path: 'templates/react-java/.mvn/settings.xml' },
      { path: 'templates/react-java/.mvn/wrapper/maven-wrapper.properties' },
      { path: 'templates/react-java/manifest.base.json.tpl' },
      { path: 'templates/react-java/ui-src/src/App.test.ts' },
      { path: 'templates/react-java/worker/pom.xml.tpl' },
      { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}WorkerMain.java.tpl' },
      { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}Worker.java.tpl' },
      { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/contract/{{javaClassPrefix}}Contract.java.tpl' },
      { path: 'templates/react-java/worker/src/test/java/{{javaPackagePath}}/PluginDevMain.java.tpl' },
      { path: 'templates/react-codex/manifest.json.tpl' },
      { path: 'templates/react-python/worker/worker.py' },
      { path: 'templates/react-go/worker/main.go.tpl' },
    ] },
    { name: '@infinia/plugin-dev', files: [{ path: 'dist/index.js' }, { path: 'dist/index.d.ts' }] },
    { name: '@infinia/plugin-sdk', files: [
      { path: 'dist/index.js' }, { path: 'dist/index.d.ts' },
      { path: 'dist/protocol.js' }, { path: 'dist/protocol.d.ts' },
    ] },
    { name: '@infinia/plugin-ui', files: [
      { path: 'dist/index.js' }, { path: 'dist/index.d.ts' }, { path: 'dist/style.css' },
    ] },
  ]))
})

test('rejects missing or source-only files', () => {
  assert.throws(() => assertPackContents([{ name: '@infinia/plugin-sdk', files: [{ path: 'test/sdk.test.mjs' }] }]), /missing|forbidden/)
})

test('rejects package tests but permits generated-project test templates', () => {
  const cliFiles = [
    { path: 'bin/fengyu.mjs' }, { path: 'src/cli.mjs' }, { path: 'src/generate.mjs' },
    { path: 'spec/manifest.schema.json' }, { path: 'templates/react-java/mvnw' },
    { path: 'templates/react-java/mvnw.cmd' },
    { path: 'templates/react-java/.mvn/settings.xml' },
    { path: 'templates/react-java/.mvn/wrapper/maven-wrapper.properties' },
    { path: 'templates/react-java/manifest.base.json.tpl' },
    { path: 'templates/react-java/ui-src/src/App.test.ts' },
    { path: 'templates/react-java/worker/pom.xml.tpl' },
    { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}WorkerMain.java.tpl' },
    { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}Worker.java.tpl' },
    { path: 'templates/react-java/worker/src/main/java/{{javaPackagePath}}/contract/{{javaClassPrefix}}Contract.java.tpl' },
    { path: 'templates/react-java/worker/src/test/java/{{javaPackagePath}}/PluginDevMain.java.tpl' },
    { path: 'templates/react-codex/manifest.json.tpl' },
    { path: 'templates/react-python/worker/worker.py' },
    { path: 'templates/react-go/worker/main.go.tpl' },
  ]
  assert.doesNotThrow(() => assertPackContents([{ name: '@infinia/plugin-cli', files: cliFiles }]))
  assert.throws(
    () => assertPackContents([{ name: '@infinia/plugin-cli', files: [...cliFiles, { path: 'test/cli.test.mjs' }] }]),
    /forbidden/,
  )
})
