import { expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

it('uses only the official SDK bridge', () => {
  const root = path.resolve('src')
  const files: string[] = []
  const walk = (dir: string) => fs.readdirSync(dir, { withFileTypes: true }).forEach(entry => {
    const item = path.join(dir, entry.name)
    entry.isDirectory() ? walk(item) : /\.(ts|vue)$/.test(item) && !item.endsWith('officialSdk.test.ts') && files.push(item)
  })
  walk(root)
  const allSource = files.map(file => fs.readFileSync(file, 'utf8')).join('\n')
  // The UI talks to the host only through the official @infinia/plugin-sdk bridge:
  // no raw postMessage, no direct fetch('/api/...'), no bundled @mdi/font (icons
  // resolve through the host-provided Vuetify mdi iconset), and no private-use
  // Unicode glyph fallbacks. MDI icon-name literals (mdi-*) are the official
  // Vuetify contract and are therefore permitted.
  expect(allSource).not.toMatch(new RegExp(`post${'Message'}\\s*\\(`))
  expect(allSource).not.toMatch(/fetch\s*\(\s*['"`]\/api\//)
  expect(allSource).not.toContain(`@mdi${'/'}font`)
  expect(allSource).not.toMatch(/[\uF000-\uF8FF]/)
  expect(fs.readFileSync(path.resolve('package.json'), 'utf8')).toContain('@infinia/plugin-sdk')
})
