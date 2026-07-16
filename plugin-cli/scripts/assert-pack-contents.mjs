#!/usr/bin/env node
import fs from 'node:fs/promises'

export function assertPackContents(packages) {
  const rules = {
    '@fengyu/plugin-cli': [
      'bin/fengyu.mjs',
      'src/cli.mjs',
      'spec/manifest.schema.json',
      'templates/vue-java/mvnw',
      'templates/vue-java/manifest.json.tpl',
      'templates/vue-java/ui-src/src/App.test.ts',
      'templates/vue-codex/manifest.json.tpl',
    ],
    '@fengyu/plugin-sdk': ['dist/index.js', 'dist/index.d.ts'],
    '@fengyu/plugin-ui': ['dist/index.js', 'dist/index.d.ts', 'dist/style.css'],
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
