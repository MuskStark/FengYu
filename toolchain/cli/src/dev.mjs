import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'
import { detectProject } from './project.mjs'
import { readManifest } from './manifest.mjs'
import { writeGenerated } from './generate.mjs'
import { runCommand, uiPackageManager } from './commands.mjs'
import { FengYuCliError } from './errors.mjs'

export async function devPlugin(root, { run = runCommand, exists = (p) => fsSync.existsSync(p) } = {}) {
  const project = await detectProject(path.resolve(root))
  const uiRoot = project.config.ui.root
  if (!uiRoot) {
    throw new FengYuCliError('fengyu dev requires a Vite UI source tree (ui-src/package.json)', {
      fix: 'run `fengyu init --id <id>` to scaffold one, or open a project that contains ui-src/',
    })
  }
  const pkg = JSON.parse(await fs.readFile(path.join(uiRoot, 'package.json'), 'utf8'))
  if (!pkg.scripts?.dev) {
    throw new FengYuCliError('ui-src/package.json must define scripts.dev', {
      file: path.join(uiRoot, 'package.json'),
      fix: 'add `"dev": "vite"` (or your dev server) to the scripts table',
    })
  }
  // Actionable dependency diagnostic: if node_modules is missing, `npm run dev`
  // (vite) fails with a confusing MODULE_NOT_FOUND, and the underlying npm/vite
  // exit code is already preserved by runCommand. Detect the gap up front so the
  // author gets a one-line fix instead of a stack trace.
  if (!exists(path.join(uiRoot, 'node_modules'))) {
    const rel = path.relative(project.root, uiRoot) || '.'
    const hasDeps =
      Object.keys(pkg.dependencies ?? {}).length || Object.keys(pkg.devDependencies ?? {}).length
    if (hasDeps) {
      throw new FengYuCliError(`UI dependencies are not installed in ${rel}`, {
        file: path.join(uiRoot, 'package.json'),
        fix: `run \`${uiPackageManager(uiRoot).bootstrap.join(' ')}\` in ${rel} before \`fengyu dev\``,
      })
    }
  }
  // Regenerate the typed RPC client before starting Vite so the dev server
  // serves fresh types. Code-first projects additionally compile the merged
  // manifest first (contract IR → target/fengyu-manifest/manifest.json).
  const codeFirst = project.manifestMode === 'code-first'
  let manifest
  if (codeFirst) {
    // Forward the injected run so tests observe the maven contract phase too
    // (build.mjs does the same); without it the phase always spawns directly.
    const generated = await (await import('./manifest-compiler.mjs')).generateCodeFirst(project.root, { project, run })
    manifest = JSON.parse(await fs.readFile(generated.manifestPath, 'utf8'))
  } else {
    manifest = await readManifest(project.root)
  }
  await writeGenerated(project, manifest, { codeFirst })
  await run(uiPackageManager(uiRoot).bin, ['run', 'dev'], { cwd: uiRoot })
}
