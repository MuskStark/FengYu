import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runCommand } from './commands.mjs'

const TEMPLATES_DIR = fileURLToPath(new URL('../templates', import.meta.url))
const VUE_CODEX_DIR = path.join(TEMPLATES_DIR, 'vue-codex')

/**
 * Scaffold a Codex-style Vue/Vuetify plugin project into `directory`.
 *
 * Recursively copies the `vue-codex` template, applying `{{pluginId}}` /
 * `{{pluginName}}` placeholders to the files that contain them, then (unless
 * `install` is false) runs `npm install`. On install failure the scaffold is
 * left in place and a descriptive error (with the original as `cause`) is thrown.
 *
 * @param {string} directory - target project root (must not already exist)
 * @param {string} id - reverse-domain plugin id, e.g. `com.example.demo`
 * @param {{ install?: boolean, run?: (command: string, args: string[], options?: object) => Promise<unknown> }} [options]
 * @returns {Promise<string>} the resolved project root
 */
export async function createPlugin(directory, id, { install = true, run = runCommand } = {}) {
  const root = path.resolve(directory)
  await ensureEmpty(root)
  const pluginName = humanName(id)
  await renderTemplate(VUE_CODEX_DIR, root, { '{{pluginId}}': id, '{{pluginName}}': pluginName })

  if (install) {
    try {
      await run('npm', ['install'], { cwd: root })
    } catch (error) {
      throw new Error(
        `Scaffold created at ${root}, but npm install failed. Run: cd ${root} && npm install`,
        { cause: error },
      )
    }
  }
  return root
}

/** Resolve a human-readable, title-cased plugin name from a reverse-domain id. */
function humanName(id) {
  const last = id.split('.').at(-1).replace(/[-_]/g, ' ')
  return last.replace(/\b\w/g, (c) => c.toUpperCase())
}

/** Reject if `root` already exists; otherwise create it (with parents). */
async function ensureEmpty(root) {
  try {
    await fs.access(root)
    throw new Error(`directory already exists: ${root}`)
  } catch (error) {
    if (error.message.startsWith('directory already')) throw error
  }
  await fs.mkdir(root, { recursive: true })
}

/**
 * Recursively copy `src` into `dest`. Files ending in `.tpl` are written
 * without that extension. Every file is read as UTF-8 and has placeholder
 * tokens substituted; files containing no tokens are left unchanged by the
 * (no-op) split/join, so only `{{pluginId}}` / `{{pluginName}}` are affected.
 */
async function renderTemplate(src, dest, replacements) {
  const entries = await fs.readdir(src, { withFileTypes: true })
  for (const entry of entries) {
    const srcPath = path.join(src, entry.name)
    const isTpl = entry.name.endsWith('.tpl')
    const outName = isTpl ? entry.name.slice(0, -'.tpl'.length) : entry.name
    const destPath = path.join(dest, outName)
    if (entry.isDirectory()) {
      await fs.mkdir(destPath, { recursive: true })
      await renderTemplate(srcPath, destPath, replacements)
    } else {
      const raw = await fs.readFile(srcPath, 'utf8')
      await fs.writeFile(destPath, applyPlaceholders(raw, replacements))
    }
  }
}

function applyPlaceholders(text, replacements) {
  let out = text
  for (const [token, value] of Object.entries(replacements)) {
    out = out.split(token).join(value)
  }
  return out
}
