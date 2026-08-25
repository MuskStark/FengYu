import fs from 'node:fs/promises'
import path from 'node:path'

import { parseManifest } from './manifest.mjs'
import { javaBasePackage } from './generate.mjs'

/**
 * One-shot migration assistant (implementation plan §11.3):
 * `fengyu migrate manifest-codegen <path>` splits an existing manifest-first
 * plugin into code-first sources WITHOUT switching the mode or deleting
 * anything — the author reviews the draft, moves their handlers over, and only
 * then deletes manifest.json by hand.
 *
 * Written:
 *   manifest.base.json              identity/ui/backend/permissions (no rpc/aiTools/flowNodes/i18n)
 *   manifest/flow-nodes.json        flowNodes overlay (when present)
 *   manifest/i18n/<locale>.json     each locale (when present)
 *   src/main/java/<pkg>/contract/<Contract>.java   annotated interface draft whose
 *       Input/Output records reuse the manifest-first generator's exact naming
 *       (Pascal(method)Input/Output, nested OwnerField records and enums), so
 *       existing handler code keeps compiling after an import rewrite.
 *
 * The report includes the pom snippet the author must add (the `fengyu-contract`
 * generate-resources/proc:only execution). The original manifest.json is NEVER
 * touched.
 */

const JAVA_RESERVED = new Set([
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class',
  'const', 'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'final',
  'finally', 'float', 'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int',
  'interface', 'long', 'native', 'new', 'package', 'private', 'protected', 'public',
  'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized', 'this',
  'throw', 'throws', 'transient', 'try', 'void', 'volatile', 'while', 'true', 'false',
  'null', 'var', 'yield', 'record', 'sealed', 'permits',
])
const IDENT = /^[A-Za-z_$][A-Za-z0-9_$]*$/

function pascal(name) {
  if (!name) return name
  // Split on hyphens too: plugin id segments like "my-plugin" must become MyPlugin,
  // not the illegal class name "My-plugin".
  return name.split(/[_-]+/).filter(Boolean)
    .map((p) => p[0].toUpperCase() + p.slice(1)).join('')
}

function javaIdent(name) {
  let s = String(name).replace(/[^A-Za-z0-9_]/g, '_')
  if (/^[0-9]/.test(s)) s = '_' + s
  if (JAVA_RESERVED.has(s)) s = s + '_'
  return s
}

function isValidEnumConstant(v) {
  const s = String(v)
  return IDENT.test(s) && !JAVA_RESERVED.has(s)
}

/** Java string literal with escapes. */
function lit(value) {
  return JSON.stringify(String(value))
}

/** Annotation attribute list rendered as ` @FengYuField(...)` or ''. */
function fieldAnnotation(prop, required, primitiveKind) {
  const attrs = []
  if (prop.description) attrs.push(`description = ${lit(prop.description)}`)
  if (prop.title) attrs.push(`title = ${lit(prop.title)}`)
  if (!primitiveKind && required) attrs.push('required = true')
  if (prop.nullable === true) attrs.push('nullable = true')
  if (typeof prop.minimum === 'number') attrs.push(`minimum = ${prop.minimum}`)
  if (typeof prop.maximum === 'number') attrs.push(`maximum = ${prop.maximum}`)
  if (prop['x-fengyu-analyze']) attrs.push(`analyze = ${lit(prop['x-fengyu-analyze'])}`)
  if (prop['x-fengyu-advanced'] === true) attrs.push('advanced = true')
  if (prop['x-fengyu-options-from']) attrs.push(`optionsFrom = ${lit(prop['x-fengyu-options-from'])}`)
  if (prop.format === 'fengyu-file' || prop.format === 'fengyu-directory') attrs.push(`format = ${lit(prop.format)}`)
  if (prop['x-fengyu-file-access'] === 'read' || prop['x-fengyu-file-access'] === 'read-write') {
    attrs.push(`fileAccess = ${lit(prop['x-fengyu-file-access'])}`)
  }
  if (!attrs.length) return ''
  return `    @FengYuField(${attrs.join(', ')})`
}

/**
 * Java field type + nested declarations for one schema property. Naming mirrors
 * generate.mjs (nested record/enum = `${owner}${Pascal(field)}`) so handler code
 * written against the manifest-first DTOs keeps compiling.
 */
function javaFieldType(prop, ownerRecord, fieldName, nestedOut, primitiveKind = false, warnings = []) {
  const nullable = prop.nullable === true
  switch (prop.type) {
    case 'string': {
      if (Array.isArray(prop.enum) && prop.enum.length) {
        if (prop.enum.every(isValidEnumConstant)) {
          const enumName = `${ownerRecord}${pascal(fieldName)}`
          nestedOut.push(`public enum ${enumName} {\n${prop.enum.map((v) => `  ${v}`).join(',\n')}\n}`)
          return enumName
        }
        warnings.push(`${ownerRecord}.${fieldName}: enum values are not all valid Java identifiers — migrated to plain String; the closed-enum constraint is lost`)
      }
      return 'String'
    }
    case 'integer': return primitiveKind ? 'int' : 'Integer'
    case 'number': return primitiveKind ? 'double' : 'Double'
    case 'boolean': return primitiveKind ? 'boolean' : 'Boolean'
    case 'array': {
      const inner = javaFieldType(prop.items ?? { type: 'string' }, ownerRecord, fieldName, nestedOut, false, warnings)
      return `List<${inner}>`
    }
    case 'object': {
      const nestedName = `${ownerRecord}${pascal(fieldName)}`
      nestedOut.push(recordBody(nestedName, prop, nestedOut, warnings))
      return nestedName
    }
    default:
      throw new Error(`cannot migrate field ${ownerRecord}.${fieldName}: unsupported type ${JSON.stringify(prop.type)}`)
  }
}

/** Full `public record X(...) { ... }` body with nested types. */
function recordBody(recordName, schema, sink, warnings = []) {
  const props = schema.properties ?? {}
  const required = new Set(schema.required ?? [])
  const nestedHere = []
  const components = []
  for (const fname of Object.keys(props).sort()) {
    const prop = props[fname]
    // Primitives only for fields the source schema actually required — the
    // processor marks every primitive required, and old manifests deliberately
    // left optional integers/booleans unwrapped to keep them omittable.
    const primitiveKind = ['integer', 'number', 'boolean'].includes(prop.type)
      && prop.nullable !== true && required.has(fname)
    const type = javaFieldType(prop, recordName, fname, nestedHere, primitiveKind, warnings)
    const annotations = []
    // A sensitive marking is a marker annotation on the component, not a
    // @FengYuField attribute — migrating it explicitly keeps the no-log /
    // no-passthrough guarantee instead of silently dropping to the name lint.
    if (prop['x-fengyu-sensitive'] === true) annotations.push('    @FengYuSensitive')
    const field = fieldAnnotation(prop, required.has(fname), primitiveKind)
    if (field) annotations.push(field)
    // Gson serializes by FIELD name — a component renamed for Java legality
    // (reserved word / non-identifier chars) must carry @SerializedName to keep
    // the original wire name, exactly like the manifest-first generator did.
    if (javaIdent(fname) !== fname) {
      annotations.unshift(`    @com.google.gson.annotations.SerializedName(${lit(fname)})`)
    }
    const component = annotations.length
      ? [annotations.join('\n'), `    ${type} ${javaIdent(fname)}`]
      : [`    ${type} ${javaIdent(fname)}`]
    components.push(component.join('\n'))
  }
  const header = components.length === 0
    ? `public record ${recordName}()`
    : `public record ${recordName}(\n${components.join(',\n')}\n)`
  const nested = nestedHere.map((body) => body.replace(/^/gm, '  ')).join('\n\n')
  return nested ? `${header} {\n${nested}\n}` : `${header} {}`
}

function contractName(id) {
  const p = pascal(id.split('.').pop() ?? id)
  return `${p || 'Plugin'}Contract`
}

/** Renders the whole contract interface source. Pass `warnings` to collect non-fatal notes. */
export function generateContractSource(manifest, { packageName, warnings = [] }) {
  const methods = Object.keys(manifest.rpc?.methods ?? {}).sort()
    .map((name) => [name, manifest.rpc.methods[name]])
  const usesList = /"type":\s*"array"/.test(JSON.stringify(manifest.rpc))
  const toolsByMethod = new Map()
  for (const tool of manifest.aiTools ?? []) {
    const existing = toolsByMethod.get(tool.method)
    if (existing) {
      throw new Error(`cannot migrate: aiTools "${existing.name}" and "${tool.name}" both expose method `
        + `"${tool.method}" — one @FengYuRpc method carries only one @FengYuAiTool; split the methods `
        + `or drop one tool before migrating`)
    }
    toolsByMethod.set(tool.method, tool)
  }

  const interfaceName = contractName(manifest.id)
  const lines = []
  lines.push('package ' + packageName + ';')
  lines.push('')
  lines.push('import fan.summer.fengyu.sdk.RpcContext;')
  lines.push('import fan.summer.fengyu.sdk.contract.FengYuAiTool;')
  lines.push('import fan.summer.fengyu.sdk.contract.FengYuContract;')
  lines.push('import fan.summer.fengyu.sdk.contract.FengYuField;')
  lines.push('import fan.summer.fengyu.sdk.contract.FengYuRpc;')
  lines.push('import fan.summer.fengyu.sdk.contract.FengYuSensitive;')
  if (usesList) lines.push('import java.util.List;')
  lines.push('')
  lines.push(`/** RPC contract for ${manifest.id} — migrated from the manifest-first manifest.json. */`)
  lines.push('@FengYuContract')
  lines.push(`public interface ${interfaceName} {`)
  for (const [name, method] of methods) {
    const inRecord = `${pascal(name)}Input`
    const outRecord = method.outputSchema ? `${pascal(name)}Output` : null
    const rpcAttrs = []
    rpcAttrs.push(`name = ${lit(name)}`)
    if (method.description) rpcAttrs.push(`description = ${lit(method.description)}`)
    if (method.timeoutSeconds) rpcAttrs.push(`timeoutSeconds = ${method.timeoutSeconds}`)
    lines.push(`    @FengYuRpc(${rpcAttrs.join(', ')})`)
    const tool = toolsByMethod.get(name)
    if (tool) {
      const toolAttrs = [`description = ${lit(tool.description)}`,
        `effect = FengYuAiTool.ToolEffect.${String(tool.effect).toUpperCase()}`]
      if (tool.name !== name) toolAttrs.unshift(`name = ${lit(tool.name)}`)
      if (tool.idempotent) toolAttrs.push('idempotent = true')
      if (tool.timeoutSeconds) toolAttrs.push(`timeoutSeconds = ${tool.timeoutSeconds}`)
      lines.push(`    @FengYuAiTool(${toolAttrs.join(', ')})`)
    }
    lines.push(`    ${outRecord ?? 'void'} ${javaIdent(name)}(${inRecord} input, RpcContext context);`)
    lines.push('')
  }
  for (const [name, method] of methods) {
    lines.push(indent4(recordBody(`${pascal(name)}Input`, method.inputSchema ?? { type: 'object' }, undefined, warnings)))
    lines.push('')
    if (method.outputSchema) {
      lines.push(indent4(recordBody(`${pascal(name)}Output`, method.outputSchema, undefined, warnings)))
      lines.push('')
    }
  }
  lines.push('}')
  lines.push('')
  return lines.join('\n').replace(/\n{3,}/g, '\n\n')
}

function indent4(text) {
  return text.split('\n').map((l) => (l.length ? '    ' + l : l)).join('\n')
}

const POM_SNIPPET = `<plugin>
  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
  <executions>
    <execution>
      <id>fengyu-contract</id>
      <phase>generate-resources</phase>
      <goals><goal>compile</goal></goals>
      <configuration>
        <proc>only</proc>
        <compilerArgs><arg>-Afengyu.contract.pluginId=PLUGIN_ID</arg></compilerArgs>
        <annotationProcessorPaths>
          <path>
            <groupId>fan.summer.fengyu.sdk</groupId>
            <artifactId>fengyu-plugin-devkit</artifactId>
            <version>\${fengyu.plugin.sdk.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </execution>
  </executions>
</plugin>`

/**
 * Run the migration. Returns the report (written paths + follow-up steps).
 * @param {string} root - manifest-first project root
 */
export async function migrateManifestCodegen(root, { workerRoot } = {}) {
  const dir = path.resolve(root)
  const text = await fs.readFile(path.join(dir, 'manifest.json'), 'utf8')
  const { manifest, errors } = parseManifest(text)
  if (errors.length) throw new Error(errors.join('\n'))
  if (!manifest.rpc?.methods || Object.keys(manifest.rpc.methods).length === 0) {
    throw new Error('manifest declares no rpc.methods — nothing to migrate')
  }
  if (await exists(path.join(dir, 'manifest.base.json'))) {
    throw new Error('manifest.base.json already exists — this project is already code-first')
  }

  // Generate the contract source FIRST: it is the step that can throw on an
  // unmigratable shape (unsupported type, two aiTools sharing one method).
  // Writing fragments after it guarantees a failed migration leaves the project
  // untouched instead of a half-migrated state that blocks retry.
  const warnings = []
  const pkg = javaBasePackage(manifest.id)
  const contractDir = workerRoot
    ? path.join(workerRoot, 'src', 'main', 'java', ...pkg.split('.'), 'contract')
    : path.join(dir, 'src', 'main', 'java', ...pkg.split('.'), 'contract')
  const contractFile = path.join(contractDir, `${contractName(manifest.id)}.java`)
  const contractSource = generateContractSource(manifest, { packageName: `${pkg}.contract`, warnings })

  const written = []
  const base = { ...manifest }
  delete base.rpc
  delete base.aiTools
  delete base.flowNodes
  delete base.i18n
  await writeJson(path.join(dir, 'manifest.base.json'), base)
  written.push('manifest.base.json')

  if (manifest.flowNodes?.length) {
    await fs.mkdir(path.join(dir, 'manifest'), { recursive: true })
    await writeJson(path.join(dir, 'manifest', 'flow-nodes.json'), { flowNodes: manifest.flowNodes })
    written.push('manifest/flow-nodes.json')
  }
  if (manifest.i18n) {
    await fs.mkdir(path.join(dir, 'manifest', 'i18n'), { recursive: true })
    for (const [locale, override] of Object.entries(manifest.i18n)) {
      await writeJson(path.join(dir, 'manifest', 'i18n', `${locale}.json`), override)
      written.push(`manifest/i18n/${locale}.json`)
    }
  }

  await fs.mkdir(contractDir, { recursive: true })
  await fs.writeFile(contractFile, contractSource, 'utf8')
  written.push(path.relative(dir, contractFile))

  return {
    root: dir,
    written,
    warnings,
    contractFile,
    pluginId: manifest.id,
    pomSnippet: POM_SNIPPET.replaceAll('PLUGIN_ID', manifest.id),
    nextSteps: [
      `add the fengyu-contract compiler execution to the worker pom (see pomSnippet)`,
      `rewrite handler imports ${pkg}.generated.<Dto> → ${pkg}.contract.${contractName(manifest.id)}.<Dto>, then delete the generated DTO records (keep PluginMethods.java)`,
      `run \`fengyu generate\` and diff target/fengyu-manifest/manifest.json against the old manifest.json — only key ordering and explicitly added fields may differ`,
      `delete manifest.json to switch to code-first (this command never does)`,
    ],
  }
}

async function writeJson(file, value) {
  await fs.writeFile(file, JSON.stringify(value, null, 2) + '\n', 'utf8')
}

async function exists(file) {
  try { await fs.access(file); return true } catch { return false }
}
