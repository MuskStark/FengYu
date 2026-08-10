import { describe, expect, it } from 'vitest'
import { messagesFor } from './i18n'

const required = [
  'mde.cardTitle',
  'mde.editor',
  'mde.preview',
  'mde.renderFailed',
]

describe.each(['en', 'zh-CN'])('Markdown messages for %s', (locale) => {
  it('contains every visible UI string', () => {
    const messages = messagesFor(locale)
    for (const key of required) expect(messages[key], key).toBeTruthy()
  })
})

it('keeps identical locale keys and falls back to English', () => {
  expect(Object.keys(messagesFor('zh-CN')).sort()).toEqual(Object.keys(messagesFor('en')).sort())
  expect(messagesFor('fr')).toBe(messagesFor('en'))
})
