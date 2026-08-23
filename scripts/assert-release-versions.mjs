import { readFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'

/**
 * Release version-mirror gate. The app version is the Maven `${revision}` property,
 * mirrored in `.mvn/maven.config`, `frontend/package.json`, `desktop/electron/package.json`,
 * and each official plugin's `manifest.base.json` (AGENTS.md / app-release skill Step 2).
 * electron-builder takes the desktop artifact version from `desktop/electron/package.json`
 * and the JAR/plugins take the tag version — a lagging mirror ships a split-brain release
 * (SPA shows the tag version, desktop artifacts + auto-updater latest*.yml ship the stale
 * one). This script fails the release BEFORE any build when a mirror disagrees with the
 * tag being released.
 *
 * Usage: node scripts/assert-release-versions.mjs <version>   (e.g. 4.0.0-beta.4)
 */
const OFFICIAL_PLUGINS = ['markdown', 'excel', 'email', 'offlinepython']

export function assertReleaseVersions(version, {
  readJson = defaultReadJson,
  root = repoRoot(),
  pomText,
  mavenConfigText,
} = {}) {
  const expected = String(version ?? '').trim()
  if (!expected) throw new Error('release version argument is required')
  const mirrors = [
    ['pom.xml <revision>', extractRevision(
      pomText ?? readFileSync(resolve(root, 'pom.xml'), 'utf8'), '<revision>')],
    ['.mvn/maven.config', extractRevision(
      mavenConfigText ?? readFileSync(resolve(root, '.mvn/maven.config'), 'utf8'), '-Drevision=')],
    ['frontend/package.json', readJson('frontend/package.json').version],
    ['desktop/electron/package.json', readJson('desktop/electron/package.json').version],
    ...OFFICIAL_PLUGINS.map(
      (plugin) => [`OfficialPlugins/plugin-${plugin}/manifest.base.json`,
        readJson(`OfficialPlugins/plugin-${plugin}/manifest.base.json`).version]),
  ]
  const stale = mirrors.filter(([, actual]) => actual !== expected)
  if (stale.length > 0) {
    const lines = stale.map(([where, actual]) => `  ${where}: ${actual}`)
    throw new Error(`Version mirrors do not match the release version ${expected}:\n${lines.join('\n')}\n`
      + 'Bump the mirrors (app-release skill Step 2) before tagging.')
  }
  return mirrors
}

function repoRoot() {
  return resolve(fileURLToPath(new URL('..', import.meta.url)))
}

function defaultReadJson(relative) {
  return JSON.parse(readFileSync(resolve(repoRoot(), relative), 'utf8'))
}

/** `kind` picks the version-bearing syntax of that file. */
function extractRevision(text, kind) {
  const source = String(text)
  const match = kind === '<revision>'
    ? /<revision>([^<]+)<\/revision>/.exec(source)
    : /-Drevision=([^\s]+)/.exec(source)
  return match ? match[1].trim() : null
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const mirrors = assertReleaseVersions(process.argv[2])
  for (const [where, actual] of mirrors) console.log(`ok  ${where} = ${actual}`)
}
