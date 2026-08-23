import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')

// The CLI bundles its own copy of the spec schemas (toolchain/cli/spec) because the
// published @infinia/plugin-cli package must carry them; toolchain/spec is the
// canonical source. Nothing auto-syncs the two, so drift is only caught by diffing —
// these tests pin byte equality and fail with the exact copy command to run.
const FILES = ['manifest.schema.json', 'flow-node.schema.json']

for (const file of FILES) {
  test(`${file} is byte-identical between toolchain/spec and toolchain/cli/spec`, () => {
    const canonical = fs.readFileSync(path.join(repo, 'toolchain', 'spec', file))
    const bundled = fs.readFileSync(path.join(repo, 'toolchain', 'cli', 'spec', file))
    assert.ok(canonical.equals(bundled),
      `${file} drifted — run: cp toolchain/spec/${file} toolchain/cli/spec/${file}`)
  })
}
