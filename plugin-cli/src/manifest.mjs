import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import { inspectArchive, readArchiveEntry } from './archive.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const schemaPath = path.resolve(here, '../spec/manifest.schema.json')
const schema = JSON.parse(fsSync.readFileSync(schemaPath, 'utf8'))
const ajv = new Ajv({ allErrors: true, strict: false })
const validateSchema = ajv.compile(schema)

export async function readManifest(root) {
  return JSON.parse(await fs.readFile(path.join(root, 'manifest.json'), 'utf8'))
}

/** Schema + semantic validation against a parsed manifest object (no filesystem access). */
export function validateManifestObject(manifest) {
  const errors = []
  if (!validateSchema(manifest)) {
    for (const e of validateSchema.errors ?? []) {
      errors.push(`manifest${e.instancePath}: ${e.message}`)
    }
  }
  const names = new Set(), methods = new Set()
  for (const tool of manifest.aiTools ?? []) {
    if (typeof tool.name !== 'string' || !tool.name) {
      errors.push('AI tool name is required')
    } else if (names.has(tool.name)) {
      errors.push(`duplicate AI tool name: ${tool.name}`)
    }
    if (typeof tool.method !== 'string' || !tool.method) {
      errors.push(`AI tool method is required for ${tool.name ?? '<unknown>'}`)
    } else if (methods.has(tool.method)) {
      errors.push(`duplicate AI tool method: ${tool.method}`)
    }
    names.add(tool.name); methods.add(tool.method)
    try {
      const parsed = JSON.parse(tool.inputSchema)
      if (parsed?.type !== 'object') errors.push(`inputSchema for ${tool.name} must have type object`)
    } catch {
      errors.push(`invalid inputSchema for ${tool.name}`)
    }
  }
  if (manifest.official === true && typeof manifest.id === 'string' && !manifest.id.startsWith('fan.summer.')) {
    errors.push('official plugin ids must use fan.summer.*')
  }
  return errors
}

function parseJarManifest(text) {
  const unfolded = text.replace(/\r?\n ([^\r\n]*)/g, '$1')
  const values = new Map()
  for (const line of unfolded.split(/\r?\n/)) {
    const index = line.indexOf(':')
    if (index > 0) values.set(line.slice(0, index), line.slice(index + 1).trim())
  }
  return values
}

async function validateWorkerJar(jar, expectedMainClass) {
  const { entries } = await inspectArchive(jar)
  const names = new Set(entries.map((entry) => entry.name))
  const manifestText = (await readArchiveEntry(jar, 'META-INF/MANIFEST.MF', { maxBytes: 1024 * 1024 })).toString('utf8')
  const actualMainClass = parseJarManifest(manifestText).get('Main-Class')
  const errors = []
  if (actualMainClass !== expectedMainClass) {
    errors.push(`worker JAR Main-Class ${actualMainClass ?? '<missing>'} does not match ${expectedMainClass}`)
  }
  const classEntry = expectedMainClass.replace(/\./g, '/') + '.class'
  if (!names.has(classEntry)) errors.push(`worker JAR is missing class entry ${classEntry}`)
  return errors
}

export async function validatePluginArchive(file) {
  const { entries } = await inspectArchive(file)
  const names = new Set(entries.filter((entry) => !entry.isDirectory).map((entry) => entry.name))
  const manifest = JSON.parse((await readArchiveEntry(file, 'manifest.json', { maxBytes: 1024 * 1024 })).toString('utf8'))
  const errors = validateManifestObject(manifest)
  if (manifest.ui?.entry && !names.has(manifest.ui.entry)) {
    errors.push(`package is missing UI entry: ${manifest.ui.entry}`)
  }
  if (manifest.backend) {
    if (manifest.backend.protocol !== 'json-rpc-2.0') errors.push('unsupported backend protocol')
    if (manifest.backend.command !== 'java -jar backend/worker.jar') {
      errors.push('backend.command must be java -jar backend/worker.jar')
    }
    if (!names.has('backend/worker.jar')) {
      errors.push('package is missing backend/worker.jar')
    } else {
      try {
        const worker = await readArchiveEntry(file, 'backend/worker.jar')
        await inspectArchive(worker)
      } catch (error) {
        errors.push(`worker JAR inspection failed: ${error.message}`)
      }
    }
  }
  return { manifest, errors }
}

/**
 * Validate a project's source manifest: object rules (schema, permissions, AI
 * tools, official-id) plus path-escape safety for ui.entry and the backend JAR.
 * Build outputs (ui.entry, backend/worker.jar) are NOT required to exist at
 * source — they are produced by the build and validated post-build by
 * {@link validateRuntimeTree}.
 */
export async function validateProjectManifest(root) {
  let manifest
  try {
    manifest = await readManifest(root)
  } catch (e) {
    return [`manifest.json: ${e.message}`]
  }
  const errors = validateManifestObject(manifest)
  const rootAbs = path.resolve(root)
  if (manifest.ui?.entry) {
    const entry = path.resolve(rootAbs, manifest.ui.entry)
    if (!entry.startsWith(rootAbs + path.sep) && entry !== rootAbs) {
      errors.push('ui.entry escapes package root')
    }
  }
  if (manifest.backend?.command) {
    const match = manifest.backend.command.match(/(?:^|\s)-jar\s+(?:"([^"]+)"|'([^']+)'|(\S+))/)
    if (match) {
      const jar = match[1] ?? match[2] ?? match[3]
      const jarPath = path.resolve(rootAbs, jar)
      if (!jarPath.startsWith(rootAbs + path.sep) && jarPath !== rootAbs) {
        errors.push('backend JAR escapes package root')
      }
    }
  }
  return errors
}

/** Legacy alias used by older build/create code paths. */
export async function validate(root) {
  return validateProjectManifest(root)
}

const FORBIDDEN_RUNTIME_ENTRIES = ['.git', 'node_modules', 'target', 'src']
const TOKEN_BEARING_FILES = ['settings.xml', '.npmrc', '.env']

/**
 * Validate the assembled runtime tree against the declared project model. This
 * runs AFTER staging and BEFORE packaging, so it catches problems the source
 * manifest validation cannot (missing build outputs, smuggled source/token
 * files, JAR manifest mismatches).
 *
 * @param {{ kind: string, root: string, config: import('./config.mjs').BuildConfig | null }} project
 * @param {string} staging - absolute staging directory
 * @returns {Promise<string[]>} list of error messages (empty = valid)
 */
export async function validateRuntimeTree(project, staging) {
  const errors = []
  let manifest
  try {
    manifest = JSON.parse(await fs.readFile(path.join(staging, 'manifest.json'), 'utf8'))
  } catch (e) {
    return [`staging manifest.json: ${e.message}`]
  }
  errors.push(...validateManifestObject(manifest))

  // ui.entry must resolve to a regular file inside staging.
  if (manifest.ui?.entry) {
    const entry = path.resolve(staging, manifest.ui.entry)
    if (!entry.startsWith(staging + path.sep) || !fsSync.existsSync(entry)) {
      errors.push(`runtime ui.entry does not exist in package: ${manifest.ui.entry}`)
    }
  }

  // Declared backend: command must reference exactly backend/worker.jar.
  if (project.config?.worker && manifest.backend?.command) {
    if (!/\bbackend\/worker\.jar\b/.test(manifest.backend.command)) {
      errors.push('declared backend.command must reference backend/worker.jar')
    }
    const jar = path.join(staging, 'backend', 'worker.jar')
    if (!fsSync.existsSync(jar)) {
      errors.push('runtime backend/worker.jar is missing')
    } else {
      // Inspect the JAR (a zip) for Main-Class + the class entry, without running it.
      try {
        const mainClass = project.config.worker.mainClass
        errors.push(...await validateWorkerJar(jar, mainClass))
      } catch (e) {
        errors.push(`worker JAR inspection failed: ${e.message}`)
      }
    }
  }

  // The runtime tree must not smuggle source, node_modules, build output, or
  // token-bearing settings files.
  await walkStaging(staging, (rel) => {
    const top = rel.split(path.sep)[0]
    if (FORBIDDEN_RUNTIME_ENTRIES.includes(top)) {
      errors.push(`runtime tree must not include: ${rel}`)
    }
    const base = path.basename(rel)
    if (TOKEN_BEARING_FILES.includes(base)) {
      errors.push(`runtime tree must not include token-bearing file: ${rel}`)
    }
  }).catch((e) => errors.push(`staging walk failed: ${e.message}`))

  return errors
}

async function walkStaging(dir, visit, base = dir) {
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) {
      throw new Error(`runtime tree contains a symlink: ${path.relative(base, path.join(dir, entry.name))}`)
    }
    const full = path.join(dir, entry.name)
    const rel = path.relative(base, full)
    if (entry.isDirectory()) {
      await walkStaging(full, visit, base)
    } else {
      visit(rel)
    }
  }
}
