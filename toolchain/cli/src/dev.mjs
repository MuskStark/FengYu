import fs from 'node:fs/promises'
import path from 'node:path'
import { detectProject } from './project.mjs'
import { readManifest } from './manifest.mjs'
import { writeGenerated } from './generate.mjs'
import { runCommand } from './commands.mjs'

export async function devPlugin(root, { run = runCommand } = {}) {
  const project = await detectProject(path.resolve(root))
  const uiRoot = project.config.ui.root
  if (!uiRoot) throw new Error('fengyu dev requires ui-src/package.json')
  const pkg = JSON.parse(await fs.readFile(path.join(uiRoot, 'package.json'), 'utf8'))
  if (!pkg.scripts?.dev) throw new Error('ui-src/package.json must define scripts.dev')
  // Regenerate the typed RPC client before starting Vite so the dev server serves fresh types.
  const manifest = await readManifest(project.root)
  await writeGenerated(project, manifest)
  await run('npm', ['run', 'dev'], { cwd: uiRoot })
}
