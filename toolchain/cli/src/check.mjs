import path from 'node:path'
import fs from 'node:fs/promises'
import { detectProject } from './project.mjs'
import { validateProjectManifest, readManifest } from './manifest.mjs'
import { checkDrift } from './generate.mjs'

export async function checkPlugin(root) {
  const dir = path.resolve(root)
  const project = await detectProject(dir)
  const errors = await validateProjectManifest(dir)
  const manifest = JSON.parse(await fs.readFile(path.join(dir, 'manifest.json'), 'utf8'))
  if (manifest.backend && !project.config.worker) errors.push('backend requires pom.xml or worker/pom.xml')
  if (!manifest.backend && project.config.worker) errors.push('pom.xml declares a worker but manifest.backend is missing')
  // Generated-code drift: `check` only reports staleness — it never writes. Authors regenerate
  // via `fengyu build` (or `fengyu dev`); CI fails if the committed generated files drift.
  errors.push(...await checkDrift(project, manifest))
  if (errors.length) throw new Error(errors.join('\n'))
  return { root: dir, ui: project.config.ui.root ? 'source' : 'static', worker: Boolean(project.config.worker) }
}
