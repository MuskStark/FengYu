import path from 'node:path'
import { detectProject } from './project.mjs'
import { validateProjectManifest, readManifest, uiEntryEscapeErrors } from './manifest.mjs'
import { checkDrift } from './generate.mjs'
import { checkLockfile, checkToolchainVersionConsistency } from './consistency.mjs'
import { detectManifestMode, compileCodeFirstManifest, runContractPhase } from './manifest-compiler.mjs'
import { FengYuCliError } from './errors.mjs'

export async function checkPlugin(root) {
  const dir = path.resolve(root)
  const project = await detectProject(dir)
  const { mode } = await detectManifestMode(dir)
  const codeFirst = mode === 'code-first'

  let manifest
  let manifestFile = path.join(dir, 'manifest.json')
  const errors = []
  if (codeFirst) {
    // Code-first: extract the contract IR into build output (never sources),
    // compile the merged manifest into build output, and validate it there.
    // Hand-written base/overlay files are validated as part of the merge
    // (boundary + duplicate-key checks).
    await runContractPhase(project)
    const compiled = await compileCodeFirstManifest(dir, { write: true })
    if (compiled.errors.length) {
      throw new FengYuCliError(compiled.errors.join('\n'), { file: path.join(dir, 'manifest.base.json') })
    }
    manifest = compiled.manifest
    manifestFile = compiled.manifestPath
    // Parity with the manifest-first branch: ui.entry (owned by manifest.base.json
    // here) must not escape the package root — caught at check time, not only by
    // the build's staging validation.
    errors.push(...uiEntryEscapeErrors(dir, manifest))
  } else {
    const sourceErrors = await validateProjectManifest(dir)
    if (sourceErrors.length) {
      throw new FengYuCliError(sourceErrors.join('\n'), { file: manifestFile })
    }
    manifest = await readManifest(dir)
  }
  if (manifest.backend && !project.config.worker) {
    errors.push('backend requires the conventional worker source for its declared language')
  }
  if (!manifest.backend && project.config.worker) {
    errors.push('worker source exists but manifest.backend is missing')
  }
  // Generated-code drift: `check` only reports staleness — it never writes. Authors regenerate
  // via `fengyu build` (or `fengyu dev` / `fengyu generate`); CI fails if the committed
  // generated files drift. Code-first checks the TS client + PluginMethods constants only.
  errors.push(...await checkDrift(project, manifest, { codeFirst }))
  // Project hygiene: a committed lockfile (once deps are installed) and a single
  // toolchain version across npm/Maven. Both are no-ops on a fresh scaffold and
  // on in-repo workspace-linked plugins.
  errors.push(...await checkLockfile(project))
  errors.push(...await checkToolchainVersionConsistency(project))
  if (errors.length) {
    throw new FengYuCliError(errors.join('\n'), { file: manifestFile })
  }
  return { root: dir, ui: project.config.ui.root ? 'source' : 'static', worker: Boolean(project.config.worker) }
}
