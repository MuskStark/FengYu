import fs from 'node:fs/promises'
import path from 'node:path'
import { loadBuildConfig } from './config.mjs'

/**
 * Classify a plugin project root and resolve its build model.
 *
 * Detection order:
 *  1. `declared` — a `fengyu.plugin.json` exists; its normalized config is returned.
 *  2. `vue-vite` — a `vite.config.*` or a `package.json` with a `dev` script that
 *     references Vite (zero-config Vue project).
 *  3. `static` — a `ui/` entry HTML + `manifest.json` with no build tooling.
 *
 * @param {string} root - project root
 * @returns {Promise<{ kind: 'declared' | 'vue-vite' | 'static', root: string, config: import('./config.mjs').BuildConfig | null }>}
 */
export async function detectProject(root) {
  const dir = path.resolve(root)
  const config = await loadBuildConfig(dir)
  if (config) return { kind: 'declared', root: dir, config }
  if (await isVueVite(dir)) return { kind: 'vue-vite', root: dir, config: null }
  return { kind: 'static', root: dir, config: null }
}

async function isVueVite(dir) {
  if (await exists(path.join(dir, 'vite.config.ts'))) return true
  if (await exists(path.join(dir, 'vite.config.js'))) return true
  try {
    const raw = await fs.readFile(path.join(dir, 'package.json'), 'utf8')
    const pkg = JSON.parse(raw)
    const hasDevScript = typeof pkg.scripts?.dev === 'string'
    const referencesVite = JSON.stringify(pkg).toLowerCase().includes('vite')
    if (hasDevScript && referencesVite) return true
  } catch {
    /* no package.json or invalid JSON → static */
  }
  return false
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
