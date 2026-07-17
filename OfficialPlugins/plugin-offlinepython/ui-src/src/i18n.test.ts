import { describe, expect, it } from 'vitest'
import { messagesFor } from './i18n'

const required = [
  'opb.project.change',
  'opb.build.openPrompt',
  'opb.build.logEmpty',
  'opb.build.completed',
  'opb.build.status.running',
  'opb.build.status.done',
  'opb.build.status.failed',
  'opb.config.openPrompt',
  'opb.deploy.logEmpty',
  'opb.deploy.status.running',
  'opb.deploy.status.done',
  'opb.deploy.status.failed',
  'opb.doctor.check',
  'opb.doctor.value',
  'opb.doctor.status',
  'opb.doctor.noChecks',
]

describe.each(['en', 'zh-CN'])('Offline Python messages for %s', (locale) => {
  it('contains every visible workflow string', () => {
    const messages = messagesFor(locale)
    for (const key of required) expect(messages[key], key).toBeTruthy()
  })
})

it('keeps identical locale keys and falls back to English', () => {
  expect(Object.keys(messagesFor('zh-CN')).sort()).toEqual(Object.keys(messagesFor('en')).sort())
  expect(messagesFor('fr')).toBe(messagesFor('en'))
})
