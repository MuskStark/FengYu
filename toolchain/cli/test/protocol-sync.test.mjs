import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Cross-language protocol & version sync net (2026-10-02 version-line decision:
 * app 4.1.x ↔ toolchain 2.1.x ↔ postMessage protocol v4 ↔ worker handshake 4).
 *
 * The toolchain's protocol constants live in FIVE languages plus a schema plus the
 * vendored SDK snapshots inside the `fengyu init` templates. Nothing else cross-checks
 * them: each language's own tests only pin its own constant, so a bump that updates,
 * say, the Java SDK but forgets the Go template passes every per-language suite and
 * ships a fragmented toolchain — a scaffolded Go plugin would then fail the host
 * handshake at first run. This suite is the single place that fails loudly and
 * precisely when any two of those surfaces drift.
 *
 * The expected values are DERIVED (from the sdk-ts package version and its
 * PROTOCOL_VERSION), never hardcoded here, so a deliberate future bump only has to
 * change the sources once for this suite to go green again.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(__dirname, '../../..')
const read = (rel) => fs.readFile(path.join(repo, rel), 'utf8')
const first = (source, pattern, rel) => {
  const match = source.match(pattern)
  assert.ok(match, `cannot find ${pattern} in ${rel} — update this sync test alongside the change`)
  return match[1]
}

async function readJson(rel) {
  return JSON.parse(await read(rel))
}

/** Collect one (label, value) pair per surface; a shared assert prints the drift table. */
function agree(pairs, what) {
  const values = new Set(pairs.map(([, value]) => value))
  if (values.size > 1) {
    const table = pairs.map(([label, value]) => `  ${label}: ${value}`).join('\n')
    assert.fail(`${what} drifted across surfaces — bump them together:\n${table}`)
  }
}

test('toolchain: all artifacts share one version, and internal references follow it', async () => {
  const version = (await readJson('toolchain/sdk-ts/package.json')).version
  const surfaces = [
    ['sdk-ts package.json', version],
    ['ui package.json', (await readJson('toolchain/ui/package.json')).version],
    ['dev package.json', (await readJson('toolchain/dev/package.json')).version],
    ['cli package.json', (await readJson('toolchain/cli/package.json')).version],
    ['sdk-ts SDK_VERSION', first(await read('toolchain/sdk-ts/src/index.ts'), /SDK_VERSION = '([^']+)'/, 'sdk-ts/src/index.ts')],
    ['sdk-python pyproject', first(await read('toolchain/sdk-python/pyproject.toml'), /^version\s*=\s*"([^"]+)"/m, 'sdk-python/pyproject.toml')],
  ]
  for (const pom of ['toolchain/sdk-java/pom.xml', 'toolchain/devkit-java/pom.xml']) {
    surfaces.push([pom, first(await read(pom), /<artifactId>fengyu-plugin-(?:sdk|devkit)<\/artifactId>\s*<version>([^<]+)<\/version>/, pom)])
  }
  agree(surfaces, 'toolchain artifact version')

  // Cross-references derived from the same version: peer ranges, the release-tag ref,
  // and the sdkVersion strings the Python/Go SDKs report in the startup handshake.
  const uiPeer = (await readJson('toolchain/ui/package.json')).peerDependencies['@infinia/plugin-sdk']
  const devPeer = (await readJson('toolchain/dev/package.json')).peerDependencies['@infinia/plugin-sdk']
  const cliScripts = (await readJson('toolchain/cli/package.json')).scripts
  agree([
    ['ui peerDependency', uiPeer],
    ['dev peerDependency', devPeer],
    ['cli verify-version ref', (cliScripts['verify-version'].match(/plugin-tooling-v(.+)$/) || [])[1]],
    ['python sdkVersion', first(await read('toolchain/sdk-python/fengyu_plugin_sdk/__init__.py'), /"sdkVersion": "([^"]+)"/, 'sdk-python/__init__.py')],
    ['go sdkVersion', first(await read('toolchain/sdk-go/worker.go'), /"sdkVersion": "([^"]+)"/, 'sdk-go/worker.go')],
    ['vendored python sdkVersion', first(await read('toolchain/cli/templates/react-python/worker/fengyu_plugin_sdk/__init__.py'), /"sdkVersion": "([^"]+)"/, 'react-python vendored SDK')],
    ['vendored go sdkVersion', first(await read('toolchain/cli/templates/react-go/worker/fengyu/worker.go.tpl'), /"sdkVersion": "([^"]+)"/, 'react-go worker.go.tpl')],
    // The DEFAULT scaffold's worker pom must resolve the SDK at the toolchain version
    // (via the {{toolingVersion}} placeholder) — a hardcoded literal here makes every
    // `fengyu init` fail checkToolchainVersionConsistency on the next toolchain release.
    ['react-java pom template sdk', first(await read('toolchain/cli/templates/react-java/worker/pom.xml.tpl'), /<fengyu\.plugin\.sdk\.version>([^<]+)<\/fengyu\.plugin\.sdk\.version>/, 'react-java pom.xml.tpl')],
  ].map(([label, value]) => [label, value === `^${version}` || value === version || value === '{{toolingVersion}}' ? version : `${value} (expected ^${version} / ${version} / {{toolingVersion}})`]),
    'toolchain version cross-reference')

  // The devkit's sdk dependency property must not lag the sdk-java artifact.
  const devkitPom = await read('toolchain/devkit-java/pom.xml')
  assert.equal(
    first(devkitPom, /<fengyu\.plugin\.sdk\.version>([^<]+)<\/fengyu\.plugin\.sdk\.version>/, 'devkit pom'),
    version,
    'devkit fengyu.plugin.sdk.version must equal the sdk-java artifact version',
  )
})

test('worker handshake protocol: host, all worker SDKs, templates, schema, and fixture agree', async () => {
  const schema = await readJson('toolchain/spec/manifest.schema.json')
  const pairs = [
    ['host PluginWorkerProtocol', first(await read('FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginWorkerProtocol.java'), /PUBLIC_PROTOCOL_VERSION = (\d+);/, 'PluginWorkerProtocol.java')],
    ['sdk-java JsonRpcWorker', first(await read('toolchain/sdk-java/src/main/java/fan/summer/fengyu/sdk/JsonRpcWorker.java'), /int PROTOCOL_VERSION = (\d+);/, 'JsonRpcWorker.java')],
    ['sdk-python', first(await read('toolchain/sdk-python/fengyu_plugin_sdk/__init__.py'), /^PROTOCOL_VERSION = (\d+)$/m, 'sdk-python/__init__.py')],
    ['sdk-go', first(await read('toolchain/sdk-go/worker.go'), /const ProtocolVersion = (\d+)/, 'sdk-go/worker.go')],
    ['manifest.schema const', String(schema.properties.backend.properties.protocolVersion.const)],
    ['smoke fixture manifest', String((await readJson('scripts/fixtures/smoke-plugin/manifest.json')).backend.protocolVersion)],
  ]
  for (const template of ['react-java', 'react-python', 'react-go']) {
    const rel = `toolchain/cli/templates/${template}/manifest.base.json.tpl`
    pairs.push([`${template} manifest template`, first(await read(rel), /"protocolVersion": (\d+)/, rel)])
  }
  // Vendored SDK snapshots are byte-copies of the canonical Go/Python SDKs, so their
  // constants are pinned the same way (and their handshakes must USE the constant, not
  // a re-hardcoded literal that could drift from it).
  const vendoredPython = 'toolchain/cli/templates/react-python/worker/fengyu_plugin_sdk/__init__.py'
  const vendoredPythonSource = await read(vendoredPython)
  pairs.push(['vendored python PROTOCOL_VERSION', first(vendoredPythonSource, /^PROTOCOL_VERSION = (\d+)$/m, vendoredPython)])
  assert.match(vendoredPythonSource, /"protocolVersion": PROTOCOL_VERSION/,
    `${vendoredPython} must answer initialize with the PROTOCOL_VERSION constant`)
  const vendoredGo = 'toolchain/cli/templates/react-go/worker/fengyu/worker.go.tpl'
  const vendoredGoSource = await read(vendoredGo)
  pairs.push(['vendored go ProtocolVersion', first(vendoredGoSource, /const ProtocolVersion = (\d+)/, vendoredGo)])
  assert.match(vendoredGoSource, /int\(version\) != ProtocolVersion/,
    `${vendoredGo} must guard initialize with the ProtocolVersion constant`)
  assert.match(vendoredGoSource, /"protocolVersion": ProtocolVersion/,
    `${vendoredGo} must answer initialize with the ProtocolVersion constant`)
  agree(pairs, 'worker handshake protocol')

  // The host rejects a manifest whose declared protocol disagrees, so the schema and the
  // host constant must match exactly (not just pairwise across the SDKs).
  const hostConstant = Number(pairs[0][1])
  assert.equal(schema.properties.backend.properties.protocolVersion.const, hostConstant,
    'manifest.schema.json backend.protocolVersion must equal the host PUBLIC_PROTOCOL_VERSION')
})

test('postMessage protocol: the sdk-ts constant is the single source and stays semver-shaped', async () => {
  const protocol = first(await read('toolchain/sdk-ts/src/protocol.ts'), /PROTOCOL_VERSION = '([^']+)'/, 'protocol.ts')
  assert.match(protocol, /^\d+\.\d+\.\d+$/, 'PROTOCOL_VERSION stays a plain semver string')
  // The dev simulator and the production host both embed this constant at build/serve
  // time through the same shared import (pinned by toolchain/dev's contract tests);
  // nothing else may carry a duplicated protocol literal. dist/ is a build output
  // (untracked): CI builds sdk-ts before this suite, and on an unbuilt fresh clone the
  // same actionable message covers "missing" and "stale".
  let dist = ''
  try {
    dist = await read('toolchain/sdk-ts/dist/protocol.js')
  } catch {
    // not built yet on this clone
  }
  assert.ok(dist.includes(`PROTOCOL_VERSION = '${protocol}'`),
    'sdk-ts dist is missing or stale — run `yarn run build` in toolchain/sdk-ts')
})

test('manifest.schema.json: toolchain/spec and toolchain/cli/spec stay byte-identical', async () => {
  const [spec, cliSpec] = await Promise.all([
    read('toolchain/spec/manifest.schema.json'),
    read('toolchain/cli/spec/manifest.schema.json'),
  ])
  assert.equal(spec, cliSpec, 'the CLI carries a byte-identical copy of toolchain/spec/manifest.schema.json — copy it after every schema edit')
})
