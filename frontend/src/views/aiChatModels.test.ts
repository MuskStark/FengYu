import { describe, expect, it } from 'vitest'
import type { AiSettings } from '@/api/types'
import { configuredChatModels } from './aiChatModels'

const settings: AiSettings = {
  mode: 'openai', activeMode: 'openai', ready: true,
  openai: { endpoint: 'https://api.openai.com', apiKey: 'sk-a***test', apiKeySet: true, model: 'gpt-5.6' },
  anthropic: { endpoint: 'https://api.anthropic.com', apiKey: '', apiKeySet: false, model: 'claude' },
  deepseek: { endpoint: '', apiKey: '', apiKeySet: false, model: '' },
  ollama: { baseUrl: 'http://127.0.0.1:11434', model: 'qwen3:8b' },
  temperature: 0.7, topP: 0.9, maxTokens: 2048, maxToolRounds: 50,
  contextWindowTokens: 32768, toolLoadingMode: 'auto', toolLoadingThreshold: 25, systemPrompt: '',
}

describe('configuredChatModels', () => {
  it('lists only host configurations that can be activated', () => {
    expect(configuredChatModels(settings)).toEqual([
      { mode: 'local', provider: 'Ollama', model: 'qwen3:8b' },
      { mode: 'openai', provider: 'OpenAI', model: 'gpt-5.6' },
    ])
  })

  it('does not expose an incomplete cloud provider as selectable', () => {
    const incomplete = structuredClone(settings)
    incomplete.openai.apiKeySet = false
    expect(configuredChatModels(incomplete).map(item => item.mode)).toEqual(['local'])
  })
})
