import { appendFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

/**
 * App release tag parser. Used ONLY by `.github/workflows/fengyu-release.yml` to turn a
 * tag like `v4.0.0-alpha.1` into `{ tag, version, appVersion, prerelease }` for the
 * `setup` job's `$GITHUB_OUTPUT`.
 *
 * Plugin-toolchain releases use a DIFFERENT tag (`plugin-tooling-vX.Y.Z`) and a DIFFERENT
 * script: `toolchain/cli/scripts/resolve-tooling-version.mjs`. This regex will REJECT
 * `plugin-tooling-v*` tags — that is intentional; do not reuse this for tooling releases.
 */
const TAG = /^v(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$/

export function resolveReleaseVersion(tag) {
  const value = String(tag ?? '').trim()
  const match = TAG.exec(value)
  if (!match) throw new Error(`Invalid release tag: ${value}`)
  const appVersion = `${match[1]}.${match[2]}.${match[3]}`
  return { tag: value, version: value.slice(1), appVersion, prerelease: Boolean(match[4]) }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = resolveReleaseVersion(process.argv[2])
  if (!process.env.GITHUB_OUTPUT) throw new Error('GITHUB_OUTPUT is required')
  appendFileSync(process.env.GITHUB_OUTPUT,
    Object.entries(result).map(([key, value]) => `${key}=${value}\n`).join(''))
}
