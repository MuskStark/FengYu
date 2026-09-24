import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/views/Settings.vue', import.meta.url), 'utf8')
const types = readFileSync(new URL('../src/api/types.ts', import.meta.url), 'utf8')
const english = readFileSync(new URL('../src/i18n/en.json', import.meta.url), 'utf8')
const chinese = readFileSync(new URL('../src/i18n/zh.json', import.meta.url), 'utf8')

test('imported MCP servers cannot be enabled from the summary-row switch', () => {
  assert.match(source, /v-if="!server\.source" class="mcp-switch"/)
  assert.match(source, /reviewImported/)
  assert.match(source, /if \(server\.source\) \{[\s\S]*selectMcpServer\(server\)/)
})

test('enabling imported MCP servers requires explicit confirmation and backend acknowledgement', () => {
  assert.match(source, /confirmImportedMcpServer\(/)
  assert.match(source, /confirmImported: Boolean\(importedSource && form\.enabled\)/)
  assert.match(types, /confirmImported\?: boolean/)
})

test('both locales explain the imported MCP execution and network risk', () => {
  assert.match(english, /importedConfirm/)
  assert.match(chinese, /importedConfirm/)
  assert.match(english, /execute a program or send network requests/)
  assert.match(chinese, /执行程序或发起网络请求/)
})
