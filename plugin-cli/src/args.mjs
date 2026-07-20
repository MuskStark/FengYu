/**
 * Option-aware command-line parser for `fengyu plugin <command> [positionals] [options]`.
 *
 * Option values (e.g. `--out dist/x.fyp`) must never be mistaken for positional
 * path arguments. Every known option is declared in a fixed table; unknown
 * options and value-less value-options are rejected explicitly so callers cannot
 * silently misinterpret an option value as a project path.
 *
 * @param {string[]} argv - the arguments after the program name (e.g. process.argv.slice(2))
 * @returns {{ group: string, command: string, positionals: string[], options: Record<string, unknown> }}
 */
export function parseCli(argv) {
  // [nameInOptions, 'value' | 'flag', value?]
  // value-options consume the next token as their value; flags set a literal.
  // Only create/build options remain — dev/install/validate have been removed:
  // development now happens in the IDE (PluginDevMain + `npm run dev` via @infinia/plugin-dev),
  // validation runs as part of `build`, and installing a .fyp is done through the host UI.
  const OPTIONS = new Map([
    ['--id', ['id', 'value']],
    ['--out', ['out', 'value']],
    ['--no-install', ['install', 'flag', false]],
    ['--ui-only', ['uiOnly', 'flag', true]],
    ['--skip-tests', ['skipTests', 'flag', true]],
  ])

  const positionals = []
  const options = { install: true }
  let i = 0
  for (; i < argv.length; i++) {
    const token = argv[i]
    if (token.startsWith('--')) {
      const spec = OPTIONS.get(token)
      if (!spec) throw new Error(`unknown option ${token}`)
      const [name, kind, literal] = spec
      if (kind === 'flag') {
        options[name] = literal
      } else {
        const next = argv[i + 1]
        if (next === undefined) throw new Error(`${token} requires a value`)
        options[name] = next
        i += 1
      }
    } else {
      positionals.push(token)
    }
  }

  const group = positionals[0]
  const command = positionals[1]
  const rest = positionals.slice(2)
  return { group, command, positionals: rest, options }
}
