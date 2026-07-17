import { describe, expect, it } from 'vitest'
import { messagesFor } from './i18n'

const required = [
  'opb.project.change',
  'opb.build.openPrompt',
  'opb.build.logEmpty',
  'opb.build.completed',
  'opb.config.openPrompt',
  'opb.deploy.logEmpty',
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
