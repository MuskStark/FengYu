import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'

/**
 * Assemble a runtime-only staging directory from a declared or legacy project.
 * Only files that ship inside the .fyp are copied here — never source, build
 * tooling, node_modules, or credentials.
 *
 * Layout produced:
 *   staging/manifest.json
 *   staging/ui/**                 <- declared ui.output, or legacy ui/ for zero-config
 *   staging/backend/worker.jar    <- declared worker.artifact
 *   staging/<package.resources to> <- each declared resource copied verbatim
 *
 * Symlinks anywhere in a copied runtime subtree are rejected (no dereference).
 *
 * @param {{ kind: string, root: string, config: import('./config.mjs').BuildConfig | null }} project
 * @param {string} staging - absolute staging directory (created if absent)
 */
export async function assembleStaging(project, staging) {
  const root = project.root
  await fs.mkdir(staging, { recursive: true })

  // 1. manifest.json (always from the project root).
  await fs.copyFile(path.join(root, 'manifest.json'), path.join(staging, 'manifest.json'))

  if (project.kind === 'declared' && project.config) {
    const cfg = project.config
    // 2. UI build output -> staging/ui.
    if (cfg.ui) {
      await copyRuntimeTree(cfg.ui.output, path.join(staging, 'ui'))
    }
    // 3. Worker artifact -> staging/backend/worker.jar.
    if (cfg.worker) {
      await fs.mkdir(path.join(staging, 'backend'), { recursive: true })
      await fs.copyFile(cfg.worker.artifact, path.join(staging, 'backend', 'worker.jar'))
    }
    // 4. Declared extra resources.
    for (const { from, to } of cfg.package.resources) {
      const dest = path.join(staging, ...to.split('/'))
      const stat = await fs.lstat(from)
      if (stat.isDirectory()) {
        await copyRuntimeTree(from, dest)
      } else {
        await fs.mkdir(path.dirname(dest), { recursive: true })
        await fs.copyFile(from, dest)
      }
    }
  } else {
    // Legacy vue-vite / static: the project's own ui/ directory is the output.
    const uiDir = path.join(root, 'ui')
    if (fsSync.existsSync(uiDir)) {
      await copyRuntimeTree(uiDir, path.join(staging, 'ui'))
    }
  }
}

/**
 * Recursively copy `src` into `dest` rejecting symlinks. Mirrors `fs.cp` but
 * with `dereference: false` and an explicit symlink check (defense in depth).
 */
async function copyRuntimeTree(src, dest) {
  const stat = await fs.lstat(src)
  if (stat.isSymbolicLink()) {
    throw new Error(`staging refuses a symlink in the runtime tree: ${src}`)
  }
  await fs.mkdir(dest, { recursive: true })
  for (const entry of await fs.readdir(src, { withFileTypes: true })) {
    const from = path.join(src, entry.name)
    const to = path.join(dest, entry.name)
    if (entry.isSymbolicLink()) {
      throw new Error(`staging refuses a symlink in the runtime tree: ${from}`)
    }
    if (entry.isDirectory()) {
      await copyRuntimeTree(from, to)
    } else {
      await fs.copyFile(from, to)
    }
  }
}
