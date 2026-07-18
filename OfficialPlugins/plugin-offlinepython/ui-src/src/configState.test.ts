import { expect, it } from 'vitest'
import { buildWorkerConfig, configForm, DEFAULT_FORM } from './configState'

it('resets omitted config fields to defaults when projects change', () => {
  expect(configForm({ python: {}, download: {} })).toEqual(DEFAULT_FORM)
})

it('preserves repository/pkg/bundle sections on a round-trip', () => {
  // The worker deserializes the saved object into a fresh BuildConfig, so the
  // UI MUST emit every section — otherwise omitted sections reset to Java
  // defaults (data loss). Verify buildWorkerConfig emits the full shape.
  const form = { ...DEFAULT_FORM, pythonVersion: '3.13.0', output: 'dist' }
  const cfg = buildWorkerConfig(form)
  expect(cfg.python?.version).toBe('3.13.0')
  expect(cfg.repository?.output).toBe('dist')
  expect(cfg.repository?.wheelDir).toBe(DEFAULT_FORM.wheelDir)
  expect(cfg.pkg?.zip).toBe(true)
  expect(cfg.bundle?.sha256).toBe(true)
})

it('defaults every field when no config is supplied', () => {
  expect(configForm(undefined)).toEqual(DEFAULT_FORM)
  expect(configForm({})).toEqual(DEFAULT_FORM)
})
