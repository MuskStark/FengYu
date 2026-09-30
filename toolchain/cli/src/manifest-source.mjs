import fs from 'node:fs/promises'
import path from 'node:path'

/**
 * Code-first manifest sources (implementation plan §4/§9).
 *
 * A plugin is code-first when it has `manifest.base.json`; it is manifest-first
 * when it has `manifest.json`. Having both is an ambiguous authoring source and
 * fails outright — the compiler must never guess which file wins.
 *
 * Code-first sources:
 *   manifest.base.json      identity, version, ui, backend, permissions… (no rpc/aiTools/flowNodes/i18n)
 *   target/fengyu-contract/contract.json   language-neutral IR produced by a code generator
 *   manifest/flow-nodes.json               flow overlay (flowNodes only)
 *   manifest/i18n/<locale>.json            locale overrides (i18n only)
 */

const BASE_FORBIDDEN = ['rpc', 'aiTools', 'flowNodes', 'i18n']
const FLOW_OVERLAY_ALLOWED = ['flowNodes']

export async function fileExists(file) {
  try { await fs.access(file); return true } catch { return false }
}

/**
 * Detect the authoring mode of a plugin root.
 *
 * @param {string} root
 * @returns {Promise<{ mode: 'code-first'|'manifest-first'|'none', error?: string }>}
 */
export async function detectManifestMode(root) {
  const dir = path.resolve(root)
  const hasBase = await fileExists(path.join(dir, 'manifest.base.json'))
  const hasManifest = await fileExists(path.join(dir, 'manifest.json'))
  if (hasBase && hasManifest) {
    return {
      mode: 'none',
      error: 'manifest.json and manifest.base.json are both present — exactly one authoring source is allowed (delete one to pick manifest-first or code-first)',
    }
  }
  if (hasBase) return { mode: 'code-first' }
  if (hasManifest) return { mode: 'manifest-first' }
  return { mode: 'none', error: 'plugin must contain manifest.json (manifest-first) or manifest.base.json (code-first)' }
}

/**
 * Read every code-first source. Reads only; no merge, no validation beyond
 * duplicate-key detection on each raw file (same contract as manifest.json).
 *
 * @param {string} root
 * @param {{ parseText?: (text: string) => { manifest: object|null, errors: string[] } }} [deps]
 * @returns {Promise<{ base: object, contract: object|null, flowOverlay: object|null, i18n: object|null, sources: Record<string,string>, errors: string[] }>}
 */
export async function readCodeFirstSources(root, { parseText } = {}) {
  const dir = path.resolve(root)
  const errors = []
  const parse = parseText ?? ((text) => ({ manifest: JSON.parse(text), errors: [] }))

  const readJson = async (rel) => {
    const text = await fs.readFile(path.join(dir, rel), 'utf8')
    const { manifest, errors: parseErrors } = parse(text)
    errors.push(...parseErrors.map((e) => `${rel}: ${e}`))
    return manifest
  }

  const base = await readJson('manifest.base.json')
  for (const forbidden of BASE_FORBIDDEN) {
    if (base && Object.prototype.hasOwnProperty.call(base, forbidden)) {
      errors.push(`manifest.base.json must not declare "${forbidden}" — code-first sources are non-overlapping (rpc/aiTools come from the generated contract; flowNodes from manifest/flow-nodes.json; i18n from manifest/i18n/)`)
    }
  }

  // The annotation processor writes the IR via Filer CLASS_OUTPUT, which lands
  // under target/classes/ — the fresh proc:only generate-resources output. The
  // sibling target/fengyu-contract/ location is what language-neutral code
  // generators (non-Java contracts) write; it is only a FALLBACK so a stale copy
  // there can never shadow a freshly extracted IR.
  const contractCandidates = [
    path.join(dir, 'target', 'classes', 'fengyu-contract', 'contract.json'),
    // Third-party scaffolds keep the Maven worker under worker/ while official
    // reactor plugins use a root pom. Both layouts are first-class.
    path.join(dir, 'worker', 'target', 'classes', 'fengyu-contract', 'contract.json'),
    path.join(dir, 'target', 'fengyu-contract', 'contract.json'),
    path.join(dir, 'worker', 'target', 'fengyu-contract', 'contract.json'),
  ]
  let contract = null
  let contractPath = contractCandidates[0]
  for (const candidate of contractCandidates) {
    if (await fileExists(candidate)) {
      contractPath = candidate
      contract = await readJson(path.relative(dir, candidate))
      break
    }
  }

  const manifestDir = path.join(dir, 'manifest')
  const flowPath = path.join(manifestDir, 'flow-nodes.json')
  let flowOverlay = null
  // Every JSON fragment under manifest/ must be recognized — a typo'd name
  // (flow_nodes.json, email.flow.json) must fail loudly, not silently never merge.
  const knownManifestJson = new Set(['flow-nodes.json'])
  const i18nDir = path.join(manifestDir, 'i18n')
  if (await fileExists(manifestDir)) {
    for (const entry of (await fs.readdir(manifestDir, { withFileTypes: true }))) {
      if (entry.isFile() && entry.name.endsWith('.json') && !knownManifestJson.has(entry.name)) {
        errors.push(`manifest/${entry.name} is not a recognized fragment — only flow-nodes.json and the i18n/ directory belong under manifest/`)
      }
      if (entry.isDirectory() && entry.name !== 'i18n') {
        errors.push(`manifest/${entry.name}/ is not a recognized fragment directory — only i18n/ belongs under manifest/`)
      }
    }
  }
  if (await fileExists(flowPath)) {
    flowOverlay = await readJson('manifest/flow-nodes.json')
    if (flowOverlay) {
      for (const key of Object.keys(flowOverlay)) {
        if (!FLOW_OVERLAY_ALLOWED.includes(key)) {
          errors.push(`manifest/flow-nodes.json must not declare "${key}" — only flowNodes belongs here`)
        }
      }
    }
  }

  let i18n = null
  if (await fileExists(i18nDir)) {
    i18n = {}
    const localeFiles = new Map()
    for (const entry of (await fs.readdir(i18nDir)).sort()) {
      if (!entry.endsWith('.json')) continue
      const locale = entry.slice(0, -'.json'.length).toLowerCase()
      if (localeFiles.has(locale)) {
        errors.push(`manifest/i18n/${localeFiles.get(locale)} and manifest/i18n/${entry} both map to locale "${locale}" — locale file names must be unique after lowercasing`)
        continue
      }
      localeFiles.set(locale, entry)
      i18n[locale] = await readJson(path.join('manifest', 'i18n', entry))
    }
    if (Object.keys(i18n).length === 0) i18n = null
  }

  return { base, contract, flowOverlay, i18n, sources: { contract: contractPath }, errors }
}

/** The code-first output locations (build outputs, never hand-edited sources). */
export function codeFirstOutputPaths(root) {
  const dir = path.resolve(root)
  return {
    contract: path.join(dir, 'target', 'fengyu-contract', 'contract.json'),
    manifest: path.join(dir, 'target', 'fengyu-manifest', 'manifest.json'),
  }
}
