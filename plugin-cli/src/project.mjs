import fs from 'node:fs/promises'
import path from 'node:path'

/**
 * Classify a plugin project root as a Vue/Vite project or a legacy static-HTML
 * project.
 *
 * Detection rule (per Task 6): a project is `vue-vite` when it has a
 * `vite.config.ts`/`vite.config.js`, OR a `package.json` that both declares a
 * `dev` script and references `vite`. Otherwise it is `static` (a `ui/` entry
 * HTML + `manifest.json` with no build tooling).
 *
 * @param {string} root - project root
 * @returns {Promise<'vue-vite' | 'static'>}
 */
export async function detectProject(root) {
  const dir = path.resolve(root)
  if (await exists(path.join(dir, 'vite.config.ts'))) return 'vue-vite'
  if (await exists(path.join(dir, 'vite.config.js'))) return 'vue-vite'
  try {
    const raw = await fs.readFile(path.join(dir, 'package.json'), 'utf8')
    const pkg = JSON.parse(raw)
    const hasDevScript = typeof pkg.scripts?.dev === 'string'
    const referencesVite = JSON.stringify(pkg).toLowerCase().includes('vite')
    if (hasDevScript && referencesVite) return 'vue-vite'
  } catch {
    /* no package.json or invalid JSON → static */
  }
  return 'static'
}

/** @returns {Promise<boolean>} true if the path is reachable. */
async function exists(file) {
  try {
    await fs.access(file)
    return true
  } catch {
    return false
  }
}
