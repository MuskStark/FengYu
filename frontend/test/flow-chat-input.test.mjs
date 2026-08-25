import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const source = await readFile(
  new URL('../src/components/agent/FlowChatPanel.vue', import.meta.url), 'utf8')

test('keeps the Flow AI composer editable while a response is streaming', () => {
  assert.match(source, /<textarea[\s\S]*:disabled="disabled"[\s\S]*@keydown="onInputKeydown"/)
  assert.doesNotMatch(source, /<textarea[\s\S]*:disabled="busy \|\| disabled"/)
  assert.match(source, /if \(busy\.value \|\| props\.disabled\) return[\s\S]*event\.preventDefault\(\)/)
})

test('isolates chat input from canvas gestures and focuses it when opened', () => {
  assert.match(source, /class="flow-chat nodrag nopan nowheel"/)
  assert.match(source, /@pointerdown\.stop/)
  assert.match(source, /@keydown\.stop/)
  assert.match(source, /onMounted\(\(\) => \{[\s\S]*inputEl\.value\?\.focus\(\)/)
})

test('does not submit while an input method is composing text', () => {
  assert.match(source, /event\.key !== 'Enter' \|\| event\.shiftKey \|\| event\.isComposing/)
})
