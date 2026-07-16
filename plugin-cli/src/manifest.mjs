import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'

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

/** Full validation against a project root: object rules plus file existence (ui.entry, backend JAR). */
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
    } else {
      try { await fs.access(entry) } catch { errors.push(`UI entry does not exist: ${manifest.ui.entry}`) }
    }
  }
  if (manifest.backend?.command) {
    const match = manifest.backend.command.match(/(?:^|\s)-jar\s+(?:"([^"]+)"|'([^']+)'|(\S+))/)
    if (match) {
      const jar = match[1] ?? match[2] ?? match[3]
      try { await fs.access(path.resolve(rootAbs, jar)) } catch { errors.push(`backend JAR does not exist: ${jar}`) }
    }
  }
  return errors
}

/** Legacy alias used by older build/create code paths. */
export async function validate(root) {
  return validateProjectManifest(root)
}
