/** Parse `fengyu <init|dev|check|build> [path] [options]`. */
export function parseCli(argv) {
  const OPTIONS = new Map([
    ['--id', ['id', 'value']],
    ['--out', ['out', 'value']],
    ['--no-install', ['install', 'flag', false]],
    ['--ui-only', ['uiOnly', 'flag', true]],
    ['--runtime', ['runtime', 'value']],
    ['--skip-tests', ['skipTests', 'flag', true]],
    ['--key', ['key', 'value']],
    ['--key-id', ['keyId', 'value']],
  ])
  const positionals = []
  const options = { install: true, help: false }
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i]
    if (token === '--help' || token === '-h') options.help = true
    else if (token.startsWith('--')) {
      const spec = OPTIONS.get(token)
      if (!spec) throw new Error(`unknown option ${token}`)
      const [name, kind, literal] = spec
      if (kind === 'flag') options[name] = literal
      else {
        const value = argv[++i]
        if (value === undefined || value.startsWith('-')) throw new Error(`${token} requires a value`)
        options[name] = value
      }
    } else positionals.push(token)
  }
  return { command: positionals[0], positionals: positionals.slice(1), options }
}
