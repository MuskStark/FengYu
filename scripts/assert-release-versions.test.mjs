import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { assertReleaseVersions } from './assert-release-versions.mjs'

const VERSION = '4.0.0-beta.4'
const POM = `<project><properties><revision>${VERSION}</revision></properties></project>`
const MAVEN_CONFIG = `-Drevision=${VERSION}\n`

function readJsonFixture(files) {
  return (relative) => {
    const content = files[relative]
    if (content === undefined) throw new Error(`no fixture for ${relative}`)
    return typeof content === 'string' ? JSON.parse(content) : content
  }
}

function fixtures(overrides = {}) {
  const files = {
    'frontend/package.json': { version: VERSION },
    'desktop/electron/package.json': { version: VERSION },
    'OfficialPlugins/plugin-markdown/manifest.base.json': { version: VERSION },
    'OfficialPlugins/plugin-excel/manifest.base.json': { version: VERSION },
    'OfficialPlugins/plugin-email/manifest.base.json': { version: VERSION },
    'OfficialPlugins/plugin-offlinepython/manifest.base.json': { version: VERSION },
  }
  for (const [key, value] of Object.entries(overrides)) files[key] = value
  return readJsonFixture(files)
}

test('all mirrors matching the release version passes', () => {
  const mirrors = assertReleaseVersions(VERSION,
    { readJson: fixtures(), pomText: POM, mavenConfigText: MAVEN_CONFIG })
  assert.equal(mirrors.length, 8)
})

test('a lagging desktop mirror fails with its location', () => {
  assert.throws(
    () => assertReleaseVersions(VERSION, {
      readJson: fixtures({ 'desktop/electron/package.json': { version: '4.0.0-beta.3' } }),
      pomText: POM,
      mavenConfigText: MAVEN_CONFIG,
    }),
    (error) => error.message.includes('desktop/electron/package.json: 4.0.0-beta.3')
      && error.message.includes(VERSION),
  )
})

test('a stale official plugin manifest fails', () => {
  assert.throws(
    () => assertReleaseVersions('4.0.0', {
      readJson: fixtures({ 'OfficialPlugins/plugin-email/manifest.base.json': { version: VERSION } }),
      pomText: POM,
      mavenConfigText: MAVEN_CONFIG,
    }),
    (error) => error.message.includes('plugin-email/manifest.base.json'),
  )
})

test('the pom revision participates in the gate', () => {
  assert.throws(
    () => assertReleaseVersions(VERSION, {
      readJson: fixtures(),
      pomText: '<project><properties><revision>4.0.0-beta.3</revision></properties></project>',
      mavenConfigText: MAVEN_CONFIG,
    }),
    (error) => error.message.includes('pom.xml'),
  )
})

test('.mvn/maven.config participates in the gate', () => {
  assert.throws(
    () => assertReleaseVersions(VERSION, {
      readJson: fixtures(),
      pomText: POM,
      mavenConfigText: '-Drevision=4.0.0-beta.3\n',
    }),
    (error) => error.message.includes('.mvn/maven.config'),
  )
})

test('the repository itself passes against the current mirrors', () => {
  // Reads the real repo files with the pom's own revision as the expected version —
  // pins that every mirror sits on one version, exactly what the release workflow's
  // setup job asserts against the tag.
  const pom = readFileSync(new URL('../pom.xml', import.meta.url), 'utf8')
  const current = /<revision>([^<]+)<\/revision>/.exec(pom)?.[1]
  assert.ok(current, 'pom.xml must declare <revision>')
  const mirrors = assertReleaseVersions(current)
  assert.equal(mirrors.length, 8)
})
