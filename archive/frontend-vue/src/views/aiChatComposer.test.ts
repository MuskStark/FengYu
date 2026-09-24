import { describe, expect, it } from 'vitest'
import { composerSubmissionText } from './aiChatComposer'

describe('AI chat composer', () => {
  it('preserves a DOM suffix that v-model has not received during IME composition', () => {
    expect(composerSubmissionText('拆分文件', '拆分文件 Excel')).toBe('拆分文件 Excel')
  })

  it('falls back to the reactive draft when the textarea is unavailable', () => {
    expect(composerSubmissionText('hello')).toBe('hello')
  })
})
