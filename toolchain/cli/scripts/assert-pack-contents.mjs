#!/usr/bin/env node
import fs from 'node:fs/promises'

export function assertPackContents(packages) {
  const rules = {
    '@infinia/plugin-cli': [
      'bin/fengyu.mjs',
      'src/cli.mjs',
      'src/generate.mjs',
      'spec/manifest.schema.json',
      'templates/vue-java/mvnw',
      'templates/vue-java/mvnw.cmd',
      'templates/vue-java/.mvn/settings.xml',
      'templates/vue-java/.mvn/wrapper/maven-wrapper.properties',
      'templates/vue-java/manifest.json.tpl',
      'templates/vue-java/ui-src/src/App.test.ts',
      'templates/vue-java/worker/pom.xml.tpl',
      'templates/vue-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}WorkerMain.java.tpl',
      'templates/vue-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}Worker.java.tpl',
      'templates/vue-java/worker/src/test/java/{{javaPackagePath}}/PluginDevMain.java.tpl',
      'templates/vue-codex/manifest.json.tpl',
    ],
    '@infinia/plugin-dev': ['dist/index.js', 'dist/index.d.ts'],
    '@infinia/plugin-sdk': ['dist/index.js', 'dist/index.d.ts', 'dist/protocol.js', 'dist/protocol.d.ts'],
    '@infinia/plugin-ui': ['dist/index.js', 'dist/index.d.ts', 'dist/style.css'],
  }
  for (const pkg of packages) {
    const names = new Set(pkg.files.map((file) => file.path))
    for (const required of rules[pkg.name] ?? []) {
      if (!names.has(required)) throw new Error(`${pkg.name} package is missing ${required}`)
    }
    for (const name of names) {
      if (/^(test|fixtures)\//.test(name) || /(^|\/)(\.env|\.npmrc)(\/|$)/.test(name)) {
        throw new Error(`${pkg.name} package contains forbidden file ${name}`)
      }
    }
  }
}

if (process.argv[1]?.endsWith('assert-pack-contents.mjs')) {
  Promise.all(process.argv.slice(2).map(async (file) => JSON.parse(await fs.readFile(file, 'utf8'))[0]))
    .then(assertPackContents)
    .catch((error) => { console.error(`Error: ${error.message}`); process.exitCode = 1 })
}
