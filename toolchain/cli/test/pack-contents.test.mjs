import test from 'node:test'
import assert from 'node:assert/strict'
import { assertPackContents } from '../scripts/assert-pack-contents.mjs'

test('accepts packages with their required published files', () => {
  assert.doesNotThrow(() => assertPackContents([
    { name: '@infinia/plugin-cli', files: [
      { path: 'bin/fengyu.mjs' }, { path: 'src/cli.mjs' }, { path: 'src/generate.mjs' },
      { path: 'spec/manifest.schema.json' }, { path: 'templates/vue-java/mvnw' },
      { path: 'templates/vue-java/mvnw.cmd' },
      { path: 'templates/vue-java/.mvn/settings.xml' },
      { path: 'templates/vue-java/.mvn/wrapper/maven-wrapper.properties' },
      { path: 'templates/vue-java/manifest.json.tpl' },
      { path: 'templates/vue-java/ui-src/src/App.test.ts' },
      { path: 'templates/vue-java/worker/pom.xml.tpl' },
      { path: 'templates/vue-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}WorkerMain.java.tpl' },
      { path: 'templates/vue-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}Worker.java.tpl' },
      { path: 'templates/vue-java/worker/src/test/java/{{javaPackagePath}}/PluginDevMain.java.tpl' },
      { path: 'templates/vue-codex/manifest.json.tpl' },
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
    { path: 'spec/manifest.schema.json' }, { path: 'templates/vue-java/mvnw' },
    { path: 'templates/vue-java/mvnw.cmd' },
    { path: 'templates/vue-java/.mvn/settings.xml' },
    { path: 'templates/vue-java/.mvn/wrapper/maven-wrapper.properties' },
    { path: 'templates/vue-java/manifest.json.tpl' },
    { path: 'templates/vue-java/ui-src/src/App.test.ts' },
    { path: 'templates/vue-java/worker/pom.xml.tpl' },
    { path: 'templates/vue-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}WorkerMain.java.tpl' },
    { path: 'templates/vue-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}Worker.java.tpl' },
    { path: 'templates/vue-java/worker/src/test/java/{{javaPackagePath}}/PluginDevMain.java.tpl' },
    { path: 'templates/vue-codex/manifest.json.tpl' },
  ]
  assert.doesNotThrow(() => assertPackContents([{ name: '@infinia/plugin-cli', files: cliFiles }]))
  assert.throws(
    () => assertPackContents([{ name: '@infinia/plugin-cli', files: [...cliFiles, { path: 'test/cli.test.mjs' }] }]),
    /forbidden/,
  )
})
