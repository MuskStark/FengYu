import path from 'node:path'
import { parseCli } from './args.mjs'
import { createPlugin } from './create.mjs'
import { buildPlugin } from './build.mjs'

export async function main(argv) {
  const { group, command, positionals, options } = parseCli(argv)
  if (group !== 'plugin') return usage()
  const root = path.resolve(positionals[0] ?? '.')

  if (command === 'create') {
    const id = options.id
    if (!id) throw new Error('--id is required')
    const install = options.install !== false
    console.log(`Created ${await createPlugin(root, id, { install, uiOnly: options.uiOnly === true })}`)
  } else if (command === 'build') {
    const result = await buildPlugin(root, { out: options.out ? path.resolve(options.out) : undefined, skipTests: options.skipTests === true })
    console.log(`Built ${result.output} (${result.files} files)`)
  } else {
    usage()
  }
}

function usage() {
  // Development happens in your IDE now (run PluginDevMain + `npm run dev`), not via the CLI.
  // Validation runs as part of `build`; installing a .fyp is done through the host UI.
  console.log('fengyu plugin <create|build> [path] [options]')
}
