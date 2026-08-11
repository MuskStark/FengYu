import path from 'node:path'
import { parseCli } from './args.mjs'
import { createPlugin } from './create.mjs'
import { buildPlugin } from './build.mjs'
import { checkPlugin } from './check.mjs'
import { devPlugin } from './dev.mjs'

export async function main(argv) {
  const { command, positionals, options } = parseCli(argv)
  if (options.help) { usage(); return }
  const root = path.resolve(positionals[0] ?? '.')
  if (command === 'init') {
    if (!options.id) throw new Error('--id is required')
    console.log(`Initialized ${await createPlugin(root, options.id, {
      install: options.install !== false,
      uiOnly: options.uiOnly === true,
    })}`)
  } else if (command === 'dev') {
    await devPlugin(root)
  } else if (command === 'check') {
    const result = await checkPlugin(root)
    console.log(`Checked ${result.root}`)
  } else if (command === 'build') {
    const result = await buildPlugin(root, {
      out: options.out ? path.resolve(options.out) : undefined,
      skipTests: options.skipTests === true,
    })
    console.log(`Built ${result.output} (${result.files} files)`)
  } else {
    throw new Error(`unknown command: ${command ?? '<missing>'}. Use 'fengyu <init|dev|check|build>'.`)
  }
}

function usage() {
  console.log('fengyu <init|dev|check|build> [path] [options]')
}
