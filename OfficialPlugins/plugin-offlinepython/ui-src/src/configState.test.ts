import { expect, it } from 'vitest'
import { configForm } from './configState'

it('resets omitted config fields to defaults when projects change', () => {
  expect(configForm({ python: {}, download: {} })).toEqual({
    pythonVersion: '3.12.10',
    platformsCsv: 'win_amd64',
    onlyBinary: true,
    recursive: true,
  })
})
