import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const source = await readFile(
  new URL('../src/components/agent/FlowChatPanel.vue', import.meta.url), 'utf8')

test('contains long tool results without adding a horizontal message scrollbar', () => {
  assert.match(source, /\.flow-chat__list\s*\{[\s\S]*?overflow-x:\s*hidden;/)
  assert.match(source, /\.flow-chat__activity\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;[^}]*\}/)
  assert.match(source, /\.flow-chat__tool\s*\{[\s\S]*?min-width:\s*0;[\s\S]*?max-width:\s*100%;[\s\S]*?overflow:\s*hidden;/)
  assert.match(source, /\.flow-chat__tool-name\s*\{[\s\S]*?min-width:\s*0;[\s\S]*?text-overflow:\s*ellipsis;/)
})
