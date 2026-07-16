import path from 'node:path'
import { parseCli } from './args.mjs'
import { createPlugin } from './create.mjs'
import { validate } from './manifest.mjs'
import { buildPlugin } from './build.mjs'
import { dev } from './dev.mjs'
import { installPlugin } from './install.mjs'

export async function main(argv) {
  const { group, command, positionals, options } = parseCli(argv)
  if (group !== 'plugin') return usage()
  const root = path.resolve(positionals[0] ?? '.')

  if (command === 'create') {
    const id = options.id
    if (!id) throw new Error('--id is required')
    const install = options.install !== false
    console.log(`Created ${await createPlugin(root, id, { install, uiOnly: options.uiOnly === true })}`)
  } else if (command === 'validate') {
    const errors = await validate(root)
    if (errors.length) throw new Error(errors.join('\n'))
    console.log('Plugin package is valid')
  } else if (command === 'build') {
    const result = await buildPlugin(root, { out: options.out ? path.resolve(options.out) : undefined, skipTests: options.skipTests === true })
    console.log(`Built ${result.output} (${result.files} files)`)
  } else if (command === 'dev') {
    await dev(root, Number(options.port ?? 4173))
  } else if (command === 'install') {
    const result = await installPlugin(root, {
      host: options.host ?? 'http://127.0.0.1:24056',
      token: options.token ?? process.env.FENGYU_TOKEN ?? '',
    })
    console.log(`Installed ${result.id ?? path.basename(root)}`)
  } else {
    usage()
  }
}

function usage() {
  console.log('fengyu plugin <create|dev|build|validate|install> [path] [options]')
}
