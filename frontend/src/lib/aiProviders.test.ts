import { describe, expect, it } from 'vitest'
import { BUILTIN_MODEL_SUGGESTIONS, providerModelRows } from './aiProviders'

describe('providerModelRows', () => {
  it('prefers the live vendor list when it answered', () => {
    const out = providerModelRows(['vendor-x', 'vendor-y'], 'configured', 'deepseek')
    expect(out.rows).toEqual(['vendor-x', 'vendor-y'])
    expect(out.fallback).toBe(false)
  })

  it('falls back to configured-first curated rows for a builtin with unreachable listing', () => {
    const out = providerModelRows([], 'deepseek-v4-flash', 'deepseek')
    expect(out.rows[0]).toBe('deepseek-v4-flash')
    expect(out.rows).toContain('deepseek-chat')
    expect(out.rows).toContain('deepseek-reasoner')
    expect(out.fallback).toBe(true)
  })

  it('dedupes the configured model against curated suggestions', () => {
    const out = providerModelRows([], 'deepseek-chat', 'deepseek')
    expect(out.rows.filter(m => m === 'deepseek-chat')).toHaveLength(1)
  })

  it('drops blank configured models', () => {
    const out = providerModelRows([], undefined, 'openai')
    expect(out.rows[0]).toBe(BUILTIN_MODEL_SUGGESTIONS.openai[0])
    expect(out.rows).not.toContain('')
  })

  it('custom providers get configured-only rows, no fallback flag', () => {
    const out = providerModelRows([], 'my-model', 'my-gateway')
    expect(out.rows).toEqual(['my-model'])
    expect(out.fallback).toBe(false)
  })
})
