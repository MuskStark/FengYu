import path from 'node:path'
import { parseCli } from './args.mjs'
import { createPlugin } from './create.mjs'
import { addRegistryComponent } from './add.mjs'
import { buildPlugin } from './build.mjs'
import { checkPlugin } from './check.mjs'
import { devPlugin } from './dev.mjs'
import { signPlugin } from './sign.mjs'
import { generateCodeFirst } from './manifest-compiler.mjs'
import { migrateManifestCodegen } from './migrate.mjs'

export async function main(argv) {
  const { command, positionals, options } = parseCli(argv)
  if (options.help) { usage(); return }
  const root = path.resolve(positionals[0] ?? '.')
  if (command === 'init') {
    if (!options.id) throw new Error('--id is required')
    console.log(`Initialized ${await createPlugin(root, options.id, {
      install: options.install !== false,
      uiOnly: options.uiOnly === true,
      runtime: options.runtime,
    })}`)
  } else if (command === 'add') {
    const name = positionals[0]
    if (!name) throw new Error("usage: fengyu add <aceternity-component> [path] — e.g. 'fengyu add sidebar'")
    const result = await addRegistryComponent(path.resolve(positionals[1] ?? '.'), name, {
      yes: options.yes === true,
      force: options.force === true,
      install: options.install !== false,
      adapt: options.rawImports !== true,
    })
    console.log(`Added ${result.components.join(', ')} → ${path.join(path.relative(process.cwd(), result.uiRoot) || '.', 'src', 'aceternity')}`)
    for (const file of result.files) console.log(`  ${file}`)
    if (result.installed.length > 0) console.log(`  deps installed: ${result.installed.join(', ')}`)
    if (result.pending.length > 0) {
      console.log(`  deps to install manually: cd ${result.uiRoot} && npm install ${result.pending.join(' ')}`)
    }
  } else if (command === 'dev') {
    await devPlugin(root)
  } else if (command === 'check') {
    const result = await checkPlugin(root)
    console.log(`Checked ${result.root}`)
  } else if (command === 'generate') {
    const result = await generateCodeFirst(root)
    console.log(`Generated ${path.relative(root, result.manifestPath)} (${result.rpcMethodCount} RPC methods, ${result.aiToolCount} AI tools)`)
    for (const file of result.generatedCode) console.log(`  ${file}`)
  } else if (command === 'migrate') {
    const sub = positionals[0]
    if (sub !== 'manifest-codegen') {
      throw new Error("usage: fengyu migrate manifest-codegen <path> — splits a manifest-first plugin into code-first sources without deleting manifest.json")
    }
    const result = await migrateManifestCodegen(path.resolve(positionals[1] ?? '.'))
    console.log(`Migrated draft for ${result.pluginId} (manifest.json is untouched):`)
    for (const file of result.written) console.log(`  ${file}`)
    console.log('\nFollow-up (manual):')
    for (const step of result.nextSteps) console.log(`  - ${step}`)
    console.log(`\npom snippet:\n${result.pomSnippet}`)
  } else if (command === 'build') {
    const result = await buildPlugin(root, {
      out: options.out ? path.resolve(options.out) : undefined,
      skipTests: options.skipTests === true,
    })
    console.log(`Built ${result.output} (${result.files} files)`)
  } else if (command === 'sign') {
    const result = await signPlugin(root, { key: options.key, keyId: options.keyId, out: options.out })
    console.log(`Signed ${result.output} (${result.keyId})`)
  } else {
    throw new Error(`unknown command: ${command ?? '<missing>'}. Use 'fengyu <init|add|dev|check|generate|build|sign>'.`)
  }
}

function usage() {
  console.log('fengyu <init|add|dev|check|generate|build|sign|migrate> [path] [options]\n  init: --runtime <java|python|go> | --ui-only\n  add: <aceternity-component> — fetch from the official registry into ui-src (--yes | --force | --no-install | --raw-imports)\n  generate: code-first projects only (manifest.base.json)\n  sign: --key <private.pem> --key-id <publisher-id>\n  migrate: manifest-codegen <path> — one-shot code-first migration draft')
}
