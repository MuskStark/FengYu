import { describe, expect, it } from 'vitest'
import { PROVIDER_PRESETS, isValidProviderId, protocolLabel, slugifyId } from './aiRegistry'

describe('aiRegistry (B4 provider registry section)', () => {
  it('slugifies display names into registry ids', () => {
    expect(slugifyId('Zhipu')).toBe('zhipu')
    expect(slugifyId('My Custom Gateway!')).toBe('my-custom-gateway')
    expect(slugifyId('  智谱 GLM ')).toBe('glm')
    expect(slugifyId('---')).toBe('')
  })

  it('validates ids against the slug grammar and builtin namespace', () => {
    expect(isValidProviderId('my-zhipu')).toBe(true)
    expect(isValidProviderId('a')).toBe(false) // too short (2-char minimum overall)
    expect(isValidProviderId('Bad_Id')).toBe(false)
    expect(isValidProviderId('openai')).toBe(false) // builtin ids are reserved
    expect(isValidProviderId('anthropic')).toBe(false)
    expect(isValidProviderId('ollama')).toBe(false)
  })

  it('presets carry a protocol, a prefilled endpoint, and a model', () => {
    for (const preset of PROVIDER_PRESETS) {
      expect(preset.key.length).toBeGreaterThan(0)
      expect(['OPENAI_CHAT', 'ANTHROPIC_MESSAGES']).toContain(preset.protocol)
      if (preset.key.startsWith('custom')) continue
      expect(preset.baseUrl).toMatch(/^https:\/\//)
      expect(preset.model.length).toBeGreaterThan(0)
    }
    // The vendor presets cover the domestic long tail the plan named.
    const keys = PROVIDER_PRESETS.map(p => p.key)
    for (const expected of ['zhipu', 'kimi', 'qwen', 'minimax', 'openrouter']) {
      expect(keys).toContain(expected)
    }
  })

  it('labels protocols compactly', () => {
    expect(protocolLabel('OPENAI_CHAT')).toBe('OpenAI')
    expect(protocolLabel('ANTHROPIC_MESSAGES')).toBe('Anthropic')
    expect(protocolLabel('OLLAMA')).toBe('Ollama')
  })
})
