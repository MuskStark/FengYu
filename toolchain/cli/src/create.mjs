import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runCommand } from './commands.mjs'
import { detectProject } from './project.mjs'
import { readManifest } from './manifest.mjs'
import { writeGenerated } from './generate.mjs'

/**
 * Toolchain version shared by the CLI, the SDK, the devkit, and generated plugin templates.
 * Read from this package's own {@code package.json} so a scaffolded plugin's {@code @infinia/*}
 * dependency ranges can never drift from the toolchain that generated it. The CLI is itself part
 * of the toolchain release, so {@code toolchain/cli/scripts/resolve-tooling-version.mjs} already
 * guarantees this version matches the other five toolchain artifacts.
 */
export const toolingVersion = JSON.parse(
  await fs.readFile(fileURLToPath(new URL('../package.json', import.meta.url)), 'utf8'),
).version

/**
 * Canonical plugin id pattern — identical to {@code toolchain/spec/manifest.schema.json}'s `id` pattern
 * ({@code ^[a-z0-9]+(?:[.-][a-z0-9]+)+$}). Applied at scaffold time so a bad id (uppercase, single
 * segment, leading separator) fails fast instead of producing a project whose manifest can't pass
 * `fengyu build` validation.
 */
const PLUGIN_ID_PATTERN = /^[a-z0-9]+(?:[.-][a-z0-9]+)+$/

const TEMPLATES_DIR = fileURLToPath(new URL('../templates', import.meta.url))
const VUE_JAVA_DIR = path.join(TEMPLATES_DIR, 'vue-java')
const VUE_CODEX_DIR = path.join(TEMPLATES_DIR, 'vue-codex')

/**
 * Scaffold a FengYu plugin project into `directory`.
 *
 * By default this produces a complete Vue + Java plugin (`vue-java`): a Vue UI
 * that calls a Java JSON-RPC worker built with the Maven Wrapper and the FengYu
 * Plugin Worker SDK. Pass `{ uiOnly: true }` to keep the lightweight UI-only
 * scaffold (`vue-codex`) instead.
 *
 * The renderer substitutes placeholders inside both file *contents* and file /
 * directory *names* (so the Java package path is derived from the id). After
 * rendering, `npm install` runs (unless `install` is false); for the full
 * template it runs inside `ui-src`, for the UI-only template inside `root`.
 *
 * @param {string} directory - target project root (must not already exist)
 * @param {string} id - reverse-domain plugin id, e.g. `com.example.demo`
 * @param {{ install?: boolean, uiOnly?: boolean, run?: (command: string, args: string[], options?: object) => Promise<unknown> }} [options]
 * @returns {Promise<string>} the resolved project root
 */
export async function createPlugin(directory, id, { install = true, uiOnly = false, run = runCommand } = {}) {
  const root = path.resolve(directory)
  // Validate the id BEFORE creating any files: a non-canonical id yields an invalid manifest (and
  // often an illegal Java package name), so scaffolding it would just defer the failure to `build`.
  if (typeof id !== 'string' || !PLUGIN_ID_PATTERN.test(id)) {
    throw new Error(
      `plugin id "${id}" is invalid: it must match ^[a-z0-9]+(?:[.-][a-z0-9]+)+$ ` +
      `(lowercase, at least two dot/dash-separated segments, e.g. com.example.demo)`,
    )
  }
  await ensureEmpty(root)
  const pluginName = humanName(id)
  const javaClassPrefix = humanName(id).replace(/[^A-Za-z0-9]/g, '')
  if (!javaClassPrefix) throw new Error(`plugin id "${id}" yields an empty Java class prefix`)
  const javaPackage = id.split('.').map((part) => part.replace(/-/g, '_')).join('.')
  const javaPackagePath = javaPackage.replace(/\./g, '/')
  const replacements = {
    '{{pluginId}}': id,
    '{{pluginName}}': pluginName,
    '{{javaPackage}}': javaPackage,
    '{{javaPackagePath}}': javaPackagePath,
    '{{javaClassPrefix}}': javaClassPrefix,
    '{{toolingVersion}}': toolingVersion,
  }

  const template = uiOnly ? VUE_CODEX_DIR : VUE_JAVA_DIR
  await renderTemplate(template, root, replacements)

  // Generate the typed RPC client from the scaffolded manifest so the new project passes
  // `fengyu check` immediately. A no-op for templates without rpc.methods (the generated tree
  // is empty); becomes active once a template declares rpc.methods.
  const project = await detectProject(root)
  const scaffoldManifest = await readManifest(root)
  await writeGenerated(project, scaffoldManifest)

  if (install) {
    const cwd = uiOnly ? root : path.join(root, 'ui-src')
    try {
      await run('npm', ['install'], { cwd })
    } catch (error) {
      throw new Error(
        `Scaffold created at ${root}, but npm install failed. Run: cd ${cwd} && npm install`,
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
 * Recursively copy `src` into `dest`. Placeholders are substituted inside both
 * file *contents* and file / directory *names* (so a Java package path like
 * `{{javaPackagePath}}/{{javaClassPrefix}}WorkerMain.java.tpl` resolves to the
 * real on-disk path). Files ending in `.tpl` are written without that suffix.
 */
async function renderTemplate(src, dest, replacements) {
  const entries = await fs.readdir(src, { withFileTypes: true })
  for (const entry of entries) {
    const srcPath = path.join(src, entry.name)
    const renderedName = applyPlaceholders(entry.name, replacements)
    const isTpl = renderedName.endsWith('.tpl')
    const outName = isTpl ? renderedName.slice(0, -'.tpl'.length) : renderedName
    const destPath = path.join(dest, outName)
    if (entry.isDirectory()) {
      await fs.mkdir(destPath, { recursive: true })
      await renderTemplate(srcPath, destPath, replacements)
    } else {
      const raw = await fs.readFile(srcPath, 'utf8')
      await fs.writeFile(destPath, applyPlaceholders(raw, replacements))
      const sourceStat = await fs.stat(srcPath)
      await fs.chmod(destPath, sourceStat.mode & 0o777)
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
