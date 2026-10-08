#!/usr/bin/env node
// Contract gate for the toolchain-release workflow's "Verify npm package contents"
// step: `npm pack --ignore-scripts --dry-run --json` output for every @infinia/*
// package must contain the published entry files and must never carry tests or
// token-bearing files. Paths are the REAL published paths — the plugin-ui
// stylesheet is derived from ui's package.json exports map so a css rename in
// the ui build cannot silently break this gate again.
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))

/** Resolve the stylesheet path plugin-ui publishes, from its exports map. */
export function uiStyleCssPath(uiPackageJson) {
  const exported = uiPackageJson?.exports?.['./style.css']
  if (typeof exported !== 'string' || !exported.startsWith('./')) {
    throw new Error('toolchain/ui/package.json must map "./style.css" to a "./dist/…" file')
  }
  return exported.slice(2)
}

export function assertPackContents(packages, uiPackageJson) {
  const rules = {
    '@infinia/plugin-cli': [
      'bin/fengyu.mjs',
      'src/cli.mjs',
      'src/generate.mjs',
      'spec/manifest.schema.json',
      'templates/react-java/mvnw',
      'templates/react-java/mvnw.cmd',
      'templates/react-java/.mvn/settings.xml',
      'templates/react-java/.mvn/wrapper/maven-wrapper.properties',
      'templates/react-java/manifest.base.json.tpl',
      'templates/react-java/ui-src/src/App.test.tsx',
      'templates/react-java/worker/pom.xml.tpl',
      'templates/react-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}WorkerMain.java.tpl',
      'templates/react-java/worker/src/main/java/{{javaPackagePath}}/{{javaClassPrefix}}Worker.java.tpl',
      'templates/react-java/worker/src/main/java/{{javaPackagePath}}/contract/{{javaClassPrefix}}Contract.java.tpl',
      'templates/react-java/worker/src/test/java/{{javaPackagePath}}/PluginDevMain.java.tpl',
      'templates/react-codex/manifest.json.tpl',
    ],
    '@infinia/plugin-dev': ['dist/index.js', 'dist/index.d.ts'],
    '@infinia/plugin-sdk': ['dist/index.js', 'dist/index.d.ts', 'dist/protocol.js', 'dist/protocol.d.ts'],
    '@infinia/plugin-ui': ['dist/index.js', 'dist/index.d.ts', uiStyleCssPath(uiPackageJson)],
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
    .then(async (packages) => {
      const uiPackageJson = JSON.parse(
        await fs.readFile(path.resolve(here, '../../ui/package.json'), 'utf8'))
      assertPackContents(packages, uiPackageJson)
    })
    .catch((error) => { console.error(`Error: ${error.message}`); process.exitCode = 1 })
}
