import fs from 'node:fs/promises'
import path from 'node:path'

import { readCodeFirstSources, codeFirstOutputPaths, detectManifestMode } from './manifest-source.mjs'

export { detectManifestMode }
import { validateManifestObject, parseManifest } from './manifest.mjs'
import { writeGenerated } from './generate.mjs'
import { resolveCommand, spawnSpec } from './commands.mjs'
import { FengYuCliError } from './errors.mjs'

/**
 * The Manifest Compiler (implementation plan §6): merges the code-first sources
 * into ONE final manifest with non-overlapping ownership — base owns identity/
 * permissions, the generated contract IR owns rpc+aiTools, the flow overlay owns
 * flowNodes, locale files own i18n. Any cross-source field or duplicate key is an
 * error; there is no "later source wins" merge.
 *
 * Output is deterministic: canonical JSON (recursively sorted object keys, 2-space
 * indent, trailing newline), so the same sources always compile byte-for-byte
 * identically and `fengyu check` can diff previews.
 */

export const IR_FORMAT_VERSION = 1

/**
 * Merge parsed code-first sources into a final manifest object.
 *
 * @param {{ base: object, contract?: object|null, flowOverlay?: object|null, i18n?: object|null }} sources
 * @returns {{ manifest: object|null, errors: string[] }}
 */
export function mergeCodeFirstManifest({ base, contract, flowOverlay, i18n }) {
  const errors = []
  if (!base || typeof base !== 'object') {
    return { manifest: null, errors: ['manifest.base.json is required for code-first plugins'] }
  }

  if (contract != null) {
    if (contract.formatVersion !== IR_FORMAT_VERSION) {
      errors.push(`contract IR formatVersion must be ${IR_FORMAT_VERSION}, got ${JSON.stringify(contract.formatVersion)}`)
    }
    if (typeof contract.pluginId === 'string' && contract.pluginId !== base.id) {
      errors.push(`contract IR pluginId ${contract.pluginId} does not match manifest.base.json id ${base.id}`)
    }
  } else {
    errors.push('generated contract is missing — run the contract generator first (`fengyu generate`)')
  }

  // Non-overlapping assembly: nothing from one source may shadow another because
  // each contributes a disjoint key set, verified above at read time. A repeated
  // key would have to come from a source declaring out-of-bounds fields.
  const manifest = { ...base }
  if (contract) {
    if (contract.rpc == null) errors.push('contract IR is missing rpc.methods')
    else manifest.rpc = contract.rpc
    if (Array.isArray(contract.aiTools) && contract.aiTools.length) manifest.aiTools = contract.aiTools
    else if (contract.aiTools != null && !Array.isArray(contract.aiTools)) errors.push('contract IR aiTools must be an array')
  }
  if (flowOverlay) manifest.flowNodes = flowOverlay.flowNodes
  if (i18n) manifest.i18n = i18n

  if (manifest.rpc == null || Object.keys(manifest.rpc.methods ?? {}).length === 0) {
    errors.push('code-first manifest has no rpc methods — the contract generator must declare at least one @FengYuRpc')
  }

  return { manifest: errors.length ? null : manifest, errors }
}

/**
 * Canonical deterministic JSON: object keys sorted recursively (arrays keep
 * their order — they are ordered data), 2-space indent, trailing newline.
 * Two compiles of the same sources produce identical bytes.
 */
export function canonicalJson(value) {
  const sorted = (v) => {
    if (Array.isArray(v)) return v.map(sorted)
    if (v && typeof v === 'object') {
      return Object.fromEntries(Object.keys(v).sort().map((k) => [k, sorted(v[k])]))
    }
    return v
  }
  return JSON.stringify(sorted(value), null, 2) + '\n'
}

/**
 * Compile a code-first project: read sources, merge, validate, and (optionally)
 * write `target/fengyu-manifest/manifest.json`. Never touches hand-written files.
 *
 * @param {string} root - project root
 * @param {{ write?: boolean }} [options]
 * @returns {Promise<{ manifest: object, text: string, manifestPath: string, errors: string[] }>}
 *   errors non-empty means the manifest is unusable (manifest is null then).
 */
export async function compileCodeFirstManifest(root, { write = false } = {}) {
  const { base, contract, flowOverlay, i18n, errors: sourceErrors } =
    await readCodeFirstSources(root, { parseText: parseManifest })
  const { manifest, errors: mergeErrors } = mergeCodeFirstManifest({ base, contract, flowOverlay, i18n })
  const errors = [...sourceErrors, ...mergeErrors]
  if (errors.length || !manifest) {
    return { manifest: null, text: null, manifestPath: codeFirstOutputPaths(root).manifest, errors }
  }
  errors.push(...validateManifestObject(manifest))

  const text = canonicalJson(manifest)
  const manifestPath = codeFirstOutputPaths(root).manifest
  if (write && errors.length === 0) {
    await fs.mkdir(path.dirname(manifestPath), { recursive: true })
    await fs.writeFile(manifestPath, text, 'utf8')
  }
  return { manifest: errors.length ? null : manifest, text, manifestPath, errors: annotateWithOrigins(errors, contract?.origins) }
}

/**
 * Appends IR origin hints to validation errors (plan §9.2): an error naming
 * `rpc.methods.<x>` also prints the Java source that declared the method, e.g.
 * `... -> RPC source: ExcelContract.java:28`.
 */
export function annotateWithOrigins(errors, origins) {
  if (!origins || typeof origins !== 'object') return errors
  return errors.map((error) => {
    const match = /rpc\.methods(?:\.|\[)([A-Za-z_][A-Za-z0-9_]*)/.exec(error)
    const origin = match && origins[`rpc.methods.${match[1]}`]
    return origin ? `${error}\n  -> RPC source: ${origin}` : error
  })
}

/**
 * The effective manifest for ANY plugin root: manifest-first reads manifest.json,
 * code-first compiles (without writing hand-written sources). Used by build/dev/
 * check so downstream steps always see one complete manifest object.
 */
export async function readEffectiveManifest(root) {
  const { mode, error } = await detectManifestMode(root)
  if (mode === 'manifest-first') {
    const text = await fs.readFile(path.join(root, 'manifest.json'), 'utf8')
    return { mode, manifest: JSON.parse(text), errors: [] }
  }
  if (mode === 'none') return { mode, manifest: null, errors: [error] }
  const { manifest, errors } = await compileCodeFirstManifest(root)
  return { mode, manifest, errors }
}

/**
 * Runs the worker's contract extraction (Maven generate-resources, proc:only —
 * writes only build output, never sources) so the IR exists for compilation.
 * Shared by `fengyu generate` (authoring), `build` (packaging), and `check`
 * (validation): every path compiles from the same freshly extracted IR.
 */
export async function runContractPhase(project, run) {
  const worker = project.config?.worker
  if (worker && (worker.runtime ?? 'java') === 'java') {
    await runMaven(['generate-resources'], worker.root, run)
  }
}

/**
 * `fengyu generate` (plan §9.1) for a code-first project:
 *   1. run the worker's contract extraction,
 *   2. compile + validate the merged manifest into target/fengyu-manifest/,
 *   3. regenerate the TypeScript RPC client + PluginMethods constants,
 *   4. never modify any hand-written file.
 *
 * Manifest-first projects are an authoring error here — their generated code
 * flows from `fengyu build|dev` as before.
 */
export async function generateCodeFirst(root, { run, project } = {}) {
  const dir = path.resolve(root)
  const { mode, error } = await detectManifestMode(dir)
  if (mode !== 'code-first') {
    throw new FengYuCliError(error ?? '`fengyu generate` requires a code-first project (manifest.base.json)', {
      fix: 'manifest-first plugins get their generated code from `fengyu build` / `fengyu dev`',
    })
  }
  const detected = project ?? await (await import('./project.mjs')).detectProject(dir)
  await runContractPhase(detected, run)

  const { manifest, text, manifestPath, errors } = await compileCodeFirstManifest(dir, { write: true })
  if (errors.length) {
    throw new FengYuCliError(errors.join('\n'), { file: manifestPath })
  }
  const written = await writeGenerated(detected, manifest, { codeFirst: true })
  return {
    manifestPath,
    manifestText: text,
    generatedCode: written.map((file) => path.relative(dir, file)),
    rpcMethodCount: Object.keys(manifest.rpc.methods).length,
    aiToolCount: (manifest.aiTools ?? []).length,
  }
}

async function runMaven(args, cwd, run) {
  const resolved = await resolveCommand(['maven', ...args], cwd)
  const spec = spawnSpec(resolved)
  const exec = run ?? (await import('./commands.mjs')).runCommand
  await exec(spec.command, spec.args, { cwd, env: resolved.env, shell: spec.shell })
}
