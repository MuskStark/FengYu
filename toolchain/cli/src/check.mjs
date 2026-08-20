import path from 'node:path'
import fs from 'node:fs/promises'
import { detectProject } from './project.mjs'
import { validateProjectManifest, readManifest } from './manifest.mjs'
import { checkDrift } from './generate.mjs'
import { checkLockfile, checkToolchainVersionConsistency } from './consistency.mjs'
import { FengYuCliError } from './errors.mjs'

export async function checkPlugin(root) {
  const dir = path.resolve(root)
  const project = await detectProject(dir)
  const errors = await validateProjectManifest(dir)
  const manifest = JSON.parse(await fs.readFile(path.join(dir, 'manifest.json'), 'utf8'))
  if (manifest.backend && !project.config.worker) {
    errors.push('backend requires the conventional worker source for its declared language')
  }
  if (!manifest.backend && project.config.worker) {
    errors.push('worker source exists but manifest.backend is missing')
  }
  // Generated-code drift: `check` only reports staleness — it never writes. Authors regenerate
  // via `fengyu build` (or `fengyu dev`); CI fails if the committed generated files drift.
  errors.push(...await checkDrift(project, manifest))
  // Project hygiene: a committed lockfile (once deps are installed) and a single
  // toolchain version across npm/Maven. Both are no-ops on a fresh scaffold and
  // on in-repo workspace-linked plugins.
  errors.push(...await checkLockfile(project))
  errors.push(...await checkToolchainVersionConsistency(project))
  if (errors.length) {
    throw new FengYuCliError(errors.join('\n'), { file: path.join(dir, 'manifest.json') })
  }
  return { root: dir, ui: project.config.ui.root ? 'source' : 'static', worker: Boolean(project.config.worker) }
}
