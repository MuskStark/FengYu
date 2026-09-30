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
const RUNTIME_OVERLAYS = {
  python: path.join(TEMPLATES_DIR, 'vue-python'),
  go: path.join(TEMPLATES_DIR, 'vue-go'),
}

/**
 * Scaffold a FengYu plugin project into `directory`.
 *
 * By default this produces a code-first Vue + Java plugin (`vue-java`): RPC
 * schemas are extracted from its @FengYuContract interface and
 * manifest.base.json contains only package/runtime metadata. Pass
 * `{ uiOnly: true }` to keep the lightweight UI-only
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
export async function createPlugin(directory, id, {
  install = true, uiOnly = false, runtime = 'java', run = runCommand,
} = {}) {
  const root = path.resolve(directory)
  // Validate the id BEFORE creating any files: a non-canonical id yields an invalid manifest (and
  // often an illegal Java package name), so scaffolding it would just defer the failure to `build`.
  if (typeof id !== 'string' || !PLUGIN_ID_PATTERN.test(id)) {
    throw new Error(
      `plugin id "${id}" is invalid: it must match ^[a-z0-9]+(?:[.-][a-z0-9]+)+$ ` +
      `(lowercase, at least two dot/dash-separated segments, e.g. com.example.demo)`,
    )
  }
  if (!['java', 'python', 'go'].includes(runtime)) {
    throw new Error(`unsupported worker runtime "${runtime}": expected java, python, or go`)
  }
  if (uiOnly && runtime !== 'java') {
    throw new Error('--ui-only cannot be combined with --runtime')
  }
  // The manifest schema deliberately permits digit-leading id segments, but a CODE scaffold
  // derives identifiers from the id (Java package/class, Python module, Go package) — a
  // digit-leading segment yields names that cannot compile/import, deferring the failure to
  // the user's first build. Fail fast for every code scaffold; only the UI-only template
  // (which derives no identifiers) tolerates them.
  if (!uiOnly && id.split(/[.-]/).some((part) => /^[0-9]/.test(part))) {
    throw new Error(
      `plugin id "${id}" is invalid for a code scaffold: segments must not start with a digit ` +
      `(they become Java package / Python module / Go package names, e.g. com.example.demo)`,
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
    '{{devWorkerCommand}}': runtime === 'python'
      ? 'python3 worker.py --dev'
      : runtime === 'go' ? 'go run . --dev' : 'PluginDevMain in your IDE',
  }

  const template = uiOnly ? VUE_CODEX_DIR : VUE_JAVA_DIR
  await renderTemplate(template, root, replacements)
  if (!uiOnly && runtime !== 'java') {
    // Reuse the canonical Vue surface, replacing only the language-specific worker/tooling.
    await fs.rm(path.join(root, 'worker'), { recursive: true, force: true })
    await fs.rm(path.join(root, '.mvn'), { recursive: true, force: true })
    await fs.rm(path.join(root, 'mvnw'), { force: true })
    await fs.rm(path.join(root, 'mvnw.cmd'), { force: true })
    // Every worker language is code-first. The overlay replaces the Java base
    // and worker with its runtime-specific base plus a language-native contract generator.
    await renderTemplate(RUNTIME_OVERLAYS[runtime], root, replacements)
  }

  // Manifest-first projects generate bindings directly here. Code-first scaffolds keep build
  // outputs out of source control; `fengyu generate|build|check` runs their contract phase.
  const project = await detectProject(root)
  if (project.manifestMode === 'manifest-first') {
    const scaffoldManifest = await readManifest(root)
    await writeGenerated(project, scaffoldManifest)
  }

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
