import type { AiMode, AiSettings } from '@/api/types'

export interface ConfiguredChatModel {
  mode: AiMode
  provider: string
  model: string
}

/** Models the host can actually activate from the persisted FengYu AI configuration. */
export function configuredChatModels(settings: AiSettings | null): ConfiguredChatModel[] {
  if (!settings) return []
  const result: ConfiguredChatModel[] = []
  if (settings.ollama.baseUrl.trim() && settings.ollama.model.trim()) {
    result.push({ mode: 'local', provider: 'Ollama', model: settings.ollama.model.trim() })
  }
  addCloud(result, 'openai', 'OpenAI', settings.openai)
  addCloud(result, 'anthropic', 'Anthropic', settings.anthropic)
  addCloud(result, 'deepseek', 'DeepSeek', settings.deepseek)
  return result
}

function addCloud(result: ConfiguredChatModel[], mode: AiMode, provider: string,
    config: AiSettings['openai']): void {
  if (config.endpoint.trim() && config.model.trim() && config.apiKeySet) {
    result.push({ mode, provider, model: config.model.trim() })
  }
}
