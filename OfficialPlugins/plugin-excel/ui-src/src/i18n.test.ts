import { describe, expect, it } from 'vitest'
import { messagesFor } from './i18n'

const required = [
  'exui.step.source',
  'exui.step.mode',
  'exui.step.output',
  'exui.step.run',
  'exui.wizard.back',
  'exui.wizard.next',
  'exui.wizard.finish',
  'exui.source.chooseFile',
  'exui.mode.bySheet.label',
  'exui.mode.byColumn.label',
  'exui.mode.complex.label',
  'exui.output.configTitle',
  'exui.run.splitting',
  'exui.complete.written',
  'exui.validation.chooseExcelFile',
  'exui.notify.unableSave',
]

describe.each(['en', 'zh-CN'])('Excel Splitter messages for %s', (locale) => {
  it('contains every visible UI string', () => {
    const messages = messagesFor(locale)
    for (const key of required) expect(messages[key], key).toBeTruthy()
  })
})

it('keeps identical locale keys and falls back to English', () => {
  expect(Object.keys(messagesFor('zh-CN')).sort()).toEqual(Object.keys(messagesFor('en')).sort())
  expect(messagesFor('fr')).toBe(messagesFor('en'))
})
