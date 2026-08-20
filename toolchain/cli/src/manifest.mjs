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
  const text = await fs.readFile(path.join(root, 'manifest.json'), 'utf8')
  return parseManifest(text).manifest
}

/**
 * Parse manifest JSON text while detecting duplicate object keys. JSON.parse
 * silently keeps the last value for a duplicated key (the parser collapses
 * duplicates before any reviver runs, so a reviver cannot see them), so a
 * manifest with two `rpc.methods.render` entries would otherwise lose the
 * first definition without any signal. {@link detectDuplicateKeys} scans the
 * raw text instead.
 *
 * @param {string} text - raw manifest file contents
 * @returns {{ manifest: object|null, errors: string[] }} best-effort parse plus duplicate-key errors
 */
export function parseManifest(text) {
  let manifest
  try {
    manifest = JSON.parse(text)
  } catch (e) {
    return { manifest: null, errors: [`manifest.json: ${e.message}`] }
  }
  const errors = []
  for (const key of detectDuplicateKeys(text)) {
    errors.push(`manifest has a duplicate key: "${key}"`)
  }
  return { manifest, errors }
}

/**
 * Detect duplicate keys in raw JSON text. Walks the text tracking the key set
 * of each open object; a quoted string is a key iff its enclosing frame is an
 * object and it is followed (ignoring whitespace) by ':'. Array indices cannot
 * duplicate in JSON, so only object members are considered. Assumes the text is
 * well-formed — callers JSON.parse the same text first and surface parse errors
 * separately, so the scanner only ever runs on valid JSON.
 *
 * @param {string} text
 * @returns {string[]} duplicate key names found
 */
function detectDuplicateKeys(text) {
  const dupes = []
  const stack = []     // 'object' | 'array'
  const keySets = []   // Set per open object frame; null for array frames
  const n = text.length
  let i = 0
  while (i < n) {
    const c = text[i]
    if (c === '{') { stack.push('object'); keySets.push(new Set()); i++; continue }
    if (c === '[') { stack.push('array'); keySets.push(null); i++; continue }
    if (c === '}' || c === ']') { stack.pop(); keySets.pop(); i++; continue }
    if (c === '"') {
      const end = scanString(text, i)
      if (stack[stack.length - 1] === 'object') {
        let j = end
        while (j < n && (text[j] === ' ' || text[j] === '\t' || text[j] === '\n' || text[j] === '\r')) j++
        if (j < n && text[j] === ':') {
          const set = keySets[keySets.length - 1]
          const key = text.slice(i + 1, end - 1)
          if (set.has(key)) dupes.push(key)
          else set.add(key)
        }
      }
      i = end
      continue
    }
    i++
  }
  return dupes
}

/** Index just past the closing quote of the string starting at text[i] === '"'. */
function scanString(text, i) {
  const n = text.length
  let j = i + 1
  while (j < n) {
    const c = text[j]
    if (c === '\\') { j += 2; continue }
    if (c === '"') return j + 1
    j++
  }
  return n
}

/**
 * Validate manifest JSON text: duplicate-key scan + object/semantic validation.
 * Use this (not {@link validateManifestObject}) whenever the raw bytes are
 * available, so the duplicate-method contract is enforced.
 */
export function validateManifestText(text) {
  const { manifest, errors } = parseManifest(text)
  if (manifest) errors.push(...validateManifestObject(manifest))
  return errors
}

/** Schema + semantic validation against a parsed manifest object (no filesystem access). */
export function validateManifestObject(manifest) {
  const errors = []
  if (!validateSchema(manifest)) {
    for (const e of validateSchema.errors ?? []) {
      errors.push(`manifest${e.instancePath}: ${e.message}`)
    }
  }
  const methods = manifest.rpc?.methods ?? {}
  const methodNames = new Set(Object.keys(methods))

  // A declared backend means a worker exists to serve RPC; an empty method
  // table would make the worker unreachable.
  if (manifest.backend && methodNames.size === 0) {
    errors.push('backend requires at least one rpc.methods entry')
  }

  const toolNames = new Set()
  for (const tool of manifest.aiTools ?? []) {
    if (typeof tool.name !== 'string' || !tool.name) {
      errors.push('AI tool name is required')
    } else if (toolNames.has(tool.name)) {
      errors.push(`duplicate AI tool name: ${tool.name}`)
    }
    toolNames.add(tool.name)

    // aiTools.method must resolve to a declared rpc method (no dangling tools).
    if (typeof tool.method !== 'string' || !tool.method) {
      errors.push(`AI tool method is required for ${tool.name ?? '<unknown>'}`)
    } else if (!methodNames.has(tool.method)) {
      errors.push(`AI tool ${tool.name} references unknown method: ${tool.method}`)
    }

    if (tool.timeoutSeconds != null) {
      validateTimeout(tool.timeoutSeconds, `aiTools[${tool.name ?? '<unknown>'}].timeoutSeconds`, errors)
    }
  }

  for (const [name, method] of Object.entries(methods)) {
    if (method?.timeoutSeconds != null) {
      validateTimeout(method.timeoutSeconds, `rpc.methods[${name}].timeoutSeconds`, errors)
    }
  }

  if (manifest.backend?.callTimeoutSeconds != null) {
    validateTimeout(manifest.backend.callTimeoutSeconds, 'backend.callTimeoutSeconds', errors)
  }

  // i18n tool overrides must reference a real aiTool name.
  if (manifest.i18n) {
    for (const [locale, override] of Object.entries(manifest.i18n)) {
      if (override?.aiTools) {
        for (const toolName of Object.keys(override.aiTools)) {
          if (!toolNames.has(toolName)) {
            errors.push(`i18n[${locale}].aiTools references unknown tool: ${toolName}`)
          }
        }
      }
    }
  }

  if (manifest.official === true && typeof manifest.id === 'string' && !manifest.id.startsWith('fan.summer.')) {
    errors.push('official plugin ids must use fan.summer.*')
  }

  // Permission hygiene: the schema enum already rejects unknown permissions, but
  // JSON arrays allow duplicates. A repeated permission is always a copy/paste
  // mistake and muddies the grant surface, so reject it at validation time.
  if (Array.isArray(manifest.permissions)) {
    const seen = new Set()
    for (const permission of manifest.permissions) {
      if (seen.has(permission)) errors.push(`duplicate permission: ${permission}`)
      else seen.add(permission)
    }
  }

  errors.push(...validateFlowNodes(manifest, toolNames, methods))
  return errors
}

/**
 * Cross-checks flowNodes against the tool surface they render: every node binds a
 * real aiTool, every declared input names a parameter the worker actually accepts,
 * and widget choices make sense for the declared value type. The canvas renders
 * declared inputs verbatim — a typo here would configure a field the tool silently
 * ignores, so it must fail at `fengyu check`/`build`, not at runtime.
 */
function validateFlowNodes(manifest, toolNames, methods) {
  const errors = []
  const toolsByName = new Map((manifest.aiTools ?? []).map((tool) => [tool.name, tool]))
  for (const node of manifest.flowNodes ?? []) {
    const label = `flowNodes[${node.tool ?? '<unknown>'}]`
    const tool = toolsByName.get(node.tool)
    if (!toolNames.has(node.tool)) {
      errors.push(`${label}.tool references unknown AI tool: ${node.tool}`)
      continue
    }
    const schema = methods[tool.method]?.inputSchema
    const params = new Set(Object.keys(schema?.properties ?? {}))
    for (const input of node.inputs ?? []) {
      if (params.size && !params.has(input.name)) {
        errors.push(`${label}.inputs[${input.name}] is not a parameter of ${tool.method}`)
      }
      const widgetTypeMismatch = WIDGET_TYPE_MISMATCH[input.widget]?.[input.type]
      if (widgetTypeMismatch) {
        errors.push(`${label}.inputs[${input.name}] widget '${input.widget}' cannot produce type '${input.type}'`)
      }
    }
  }
  return errors
}

/**
 * Widget→type pairs that can never be satisfied: the editor would render one shape
 * while the variable picker filters for another. Text widgets may still bind
 * object/array references (exact references keep their parsed type), so those pairs
 * stay legal; `any` is always allowed as the escape hatch.
 */
const WIDGET_TYPE_MISMATCH = {
  number: { string: true, boolean: true, array: true, object: true, file: true },
  switch: { string: true, number: true, array: true, object: true, file: true },
  text: { boolean: true },
}

/**
 * Per-call / per-tool timeout bounds. Mirrors the host-side caps in
 * {@code FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java}
 * ({@code MAX_TIMEOUT_SECONDS = 600}) and {@code PluginPackageService.java}. Keeping the
 * CLI validator and the host installer in sync means a package that passes `fengyu build`'s
 * built-in staging validation also passes host-side install validation.
 */
export const TIMEOUT_MIN_SECONDS = 1
export const TIMEOUT_MAX_SECONDS = 600

function validateTimeout(value, field, errors) {
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    errors.push(`${field} must be an integer`)
    return
  }
  if (value < TIMEOUT_MIN_SECONDS || value > TIMEOUT_MAX_SECONDS) {
    errors.push(`${field} must be between ${TIMEOUT_MIN_SECONDS} and ${TIMEOUT_MAX_SECONDS} seconds`)
  }
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
  if (!actualMainClass) {
    errors.push('worker JAR manifest is missing Main-Class')
  } else if (expectedMainClass && actualMainClass !== expectedMainClass) {
    errors.push(`worker JAR Main-Class ${actualMainClass ?? '<missing>'} does not match ${expectedMainClass}`)
  }
  const mainClass = expectedMainClass ?? actualMainClass
  if (mainClass) {
    const classEntry = mainClass.replace(/\./g, '/') + '.class'
    if (!names.has(classEntry)) errors.push(`worker JAR is missing class entry ${classEntry}`)
  }
  return errors
}

export async function validatePluginArchive(file) {
  const { entries } = await inspectArchive(file)
  const names = new Set(entries.filter((entry) => !entry.isDirectory).map((entry) => entry.name))
  const manifestText = (await readArchiveEntry(file, 'manifest.json', { maxBytes: 1024 * 1024 })).toString('utf8')
  const errors = validateManifestText(manifestText)
  const manifest = JSON.parse(manifestText)
  if (manifest.ui?.entry && !names.has(manifest.ui.entry)) {
    errors.push(`package is missing UI entry: ${manifest.ui.entry}`)
  }
  if (manifest.backend) {
    const runtime = manifest.backend.runtime ?? 'java'
    const artifact = workerArtifact(runtime)
    if (!names.has(artifact)) {
      errors.push(`package is missing ${artifact}`)
    } else if (runtime === 'java') {
      try {
        const worker = await readArchiveEntry(file, artifact)
        errors.push(...await validateWorkerJar(worker))
      } catch (error) {
        errors.push(`worker JAR inspection failed: ${error.message}`)
      }
    }
  }
  return { manifest, errors }
}

/**
 * Validate a project's source manifest: object rules (schema, permissions, RPC
 * method table, AI tools, official-id) plus path-escape safety for ui.entry.
 * Build outputs (ui.entry and the runtime-specific backend worker) are NOT required to exist at
 * source — they are produced by the build and validated post-build by
 * {@link validateRuntimeTree}.
 */
export async function validateProjectManifest(root) {
  let text
  try {
    text = await fs.readFile(path.join(root, 'manifest.json'), 'utf8')
  } catch (e) {
    return [`manifest.json: ${e.message}`]
  }
  const errors = validateManifestText(text)
  const manifest = JSON.parse(text)
  const rootAbs = path.resolve(root)
  if (manifest.ui?.entry) {
    const entry = path.resolve(rootAbs, manifest.ui.entry)
    if (!entry.startsWith(rootAbs + path.sep) && entry !== rootAbs) {
      errors.push('ui.entry escapes package root')
    }
  }
  return errors
}

const FORBIDDEN_RUNTIME_ENTRIES = ['.git', 'node_modules', 'target', 'src']
const TOKEN_BEARING_FILES = ['settings.xml', '.npmrc', '.env']

/**
 * Validate the assembled runtime tree against the declared project model. This
 * runs AFTER staging and BEFORE packaging, so it catches problems the source
 * manifest validation cannot (missing build outputs, smuggled source/token
 * files, JAR manifest mismatches).
 *
 * @param {{ kind: string, root: string, config: object }} project
 * @param {string} staging - absolute staging directory
 * @returns {Promise<string[]>} list of error messages (empty = valid)
 */
export async function validateRuntimeTree(project, staging) {
  const errors = []
  let text
  try {
    text = await fs.readFile(path.join(staging, 'manifest.json'), 'utf8')
  } catch (e) {
    return [`staging manifest.json: ${e.message}`]
  }
  errors.push(...validateManifestText(text))
  const manifest = JSON.parse(text)

  if (manifest.backend && !project.config?.worker) {
    errors.push('runtime backend requires the conventional worker source for its declared language')
  }

  // ui.entry must resolve to a regular file inside staging.
  if (manifest.ui?.entry) {
    const entry = path.resolve(staging, manifest.ui.entry)
    if (!entry.startsWith(staging + path.sep)
        || !fsSync.existsSync(entry)
        || !fsSync.statSync(entry).isFile()) {
      errors.push(`runtime ui.entry does not exist in package: ${manifest.ui.entry}`)
    }
  }

  // Declared backend: runtime selects one conventional, host-owned artifact path.
  if (manifest.backend) {
    const runtime = manifest.backend.runtime ?? 'java'
    const artifact = workerArtifact(runtime)
    const worker = path.join(staging, ...artifact.split('/'))
    if (!fsSync.existsSync(worker)) {
      errors.push(`runtime ${artifact} is missing`)
    } else if (runtime === 'java') {
      // Inspect the JAR (a zip) for Main-Class + the class entry, without running it.
      try {
        errors.push(...await validateWorkerJar(worker))
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

function workerArtifact(runtime) {
  if (runtime === 'python') return 'backend/worker.py'
  if (runtime === 'go') return process.platform === 'win32' ? 'backend/worker.exe' : 'backend/worker'
  return 'backend/worker.jar'
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
