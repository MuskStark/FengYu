#!/usr/bin/env node
// Copies the canonical ../plugin-spec/manifest.schema.json into plugin-cli/spec/
// byte-for-byte, so the npm package ships an immutable schema copy without
// forcing consumers to depend on a sibling repo directory. The source of truth
// is always plugin-spec/manifest.schema.json; this generated copy is never edited.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = path.resolve(here, '../../plugin-spec/manifest.schema.json')
const target = path.resolve(here, '../spec/manifest.schema.json')
const checking = process.argv.includes('--check')

const buffer = fs.readFileSync(source)
const exists = fs.existsSync(target)
if (checking) {
  const current = exists ? fs.readFileSync(target) : null
  if (!current || !current.equals(buffer)) {
    console.error(`spec/manifest.schema.json is out of sync with plugin-spec/manifest.schema.json. Run: npm run sync-spec`)
    process.exit(1)
  }
  console.log('spec/manifest.schema.json is in sync.')
  process.exit(0)
}

fs.mkdirSync(path.dirname(target), { recursive: true })
fs.writeFileSync(target, buffer)
console.log(`Synced ${path.relative(path.resolve(here, '..'), target)}`)
