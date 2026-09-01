import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

/**
 * Source-contract regression for the RC store panels (review R-3/N-5): the
 * unified-sources switch must label the action consistently with its title,
 * destructive/permission-changing actions stay behind confirmAction, remote
 * homepage links stay URI-sanitized, and builtin skills stay read-only.
 */
const sourcesPanel = await readFile(new URL('../src/components/store/UnifiedSourcesPanel.vue', import.meta.url), 'utf8')
const skillsPanel = await readFile(new URL('../src/components/store/SkillsMarketPanel.vue', import.meta.url), 'utf8')

test('unified-sources switch label matches the toggle action of its title', () => {
  const title = /:title="e\.enabled \? t\('store\.sources\.disable'\) : t\('store\.sources\.enable'\)"/
  const label = /\{\{ e\.enabled \? t\('store\.sources\.disable'\) : t\('store\.sources\.enable'\) \}\}/
  assert.match(sourcesPanel, title)
  assert.match(sourcesPanel, label)
})

test('unified-sources switch toggles the enabled state it displays', () => {
  assert.match(sourcesPanel, /:checked="e\.enabled"/)
  assert.match(sourcesPanel, /@change="storeView\.setEnabled\(e\.uid, !e\.enabled\)"/)
})

test('store updates with permission changes stay behind a confirmation', () => {
  assert.match(sourcesPanel, /confirmAction\(t\('store\.sources\.confirmUpdatePermissions'\)\)/)
})

test('remote homepage links cannot smuggle script URIs', () => {
  assert.match(sourcesPanel, /safeHomepage = computed\(\(\) => \{/)
  assert.match(sourcesPanel, /\^\(https\?:|mailto:\)/)
  assert.match(sourcesPanel, /v-if="safeHomepage"/)
})

test('builtin skills render read-only and never offer marketplace actions', () => {
  assert.match(skillsPanel, /source === 'BUILTIN'/)
  assert.match(skillsPanel, /v-if="!card\.installed && !card\.builtin"/)
  assert.match(skillsPanel, /skillsMarket\.builtinReadonly/)
})

test('skill uninstalls require an explicit confirmation', () => {
  assert.match(skillsPanel, /confirmAction\(t\('skillsMarket\.confirmUninstall'\)\)/)
})

test('skill detail markdown goes through the sanitizer, never raw v-html', () => {
  assert.match(skillsPanel, /import \{ renderMarkdown \} from '@\/security\/markdown'/)
  assert.match(skillsPanel, /return renderMarkdown\(src\)/)
  assert.doesNotMatch(skillsPanel, /v-html="src"/)
})
