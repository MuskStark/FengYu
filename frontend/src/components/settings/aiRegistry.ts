import type { AiProviderProtocol } from '@/services/types'

/**
 * Registry-section logic (B4): the preset table, id slugification, and the
 * create-request mapping — extracted so the behavior is unit-testable without a
 * DOM, matching this repo's logic-level test style.
 */

/** One first-party preset: protocol + prefilled endpoint for the add dialog. */
export interface ProviderPreset {
  key: string
  protocol: AiProviderProtocol
  baseUrl: string
  /** Suggested first model id (editable). */
  model: string
}

/**
 * Built-in presets for common vendors — pure frontend data; the registry accepts
 * ANY compatible endpoint, these just prefill the form. baseUrl rules: OpenAI-
 * compatible roots carry no /v1 here (the backend normalizes per protocol).
 */
export const PROVIDER_PRESETS: readonly ProviderPreset[] = [
  { key: 'zhipu', protocol: 'OPENAI_CHAT', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-5.3' },
  { key: 'kimi', protocol: 'OPENAI_CHAT', baseUrl: 'https://api.moonshot.cn/v1', model: 'kimi-k3' },
  { key: 'qwen', protocol: 'OPENAI_CHAT', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen3.8-max' },
  { key: 'minimax', protocol: 'OPENAI_CHAT', baseUrl: 'https://api.minimaxi.com/v1', model: 'minimax-m3' },
  { key: 'openrouter', protocol: 'OPENAI_CHAT', baseUrl: 'https://openrouter.ai/api/v1', model: 'anthropic/claude-sonnet-4.5' },
  { key: 'anthropic-compat', protocol: 'ANTHROPIC_MESSAGES', baseUrl: 'https://api.anthropic.com', model: 'claude-sonnet-4-5' },
  { key: 'custom-openai', protocol: 'OPENAI_CHAT', baseUrl: '', model: '' },
  { key: 'custom-anthropic', protocol: 'ANTHROPIC_MESSAGES', baseUrl: '', model: '' },
] as const

/** Lowercase-slugifies a display name into a registry id (a-z, 0-9, -). */
export function slugifyId(displayName: string): string {
  return displayName
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
}

/** True when the id is a valid registry slug that does not shadow the built-ins. */
export function isValidProviderId(id: string): boolean {
  return /^[a-z0-9][a-z0-9-]{1,39}$/.test(id) && !['openai', 'anthropic', 'deepseek', 'ollama'].includes(id)
}

/** Protocol → short badge label. */
export function protocolLabel(protocol: AiProviderProtocol): string {
  switch (protocol) {
    case 'OPENAI_CHAT':
      return 'OpenAI'
    case 'ANTHROPIC_MESSAGES':
      return 'Anthropic'
    case 'OLLAMA':
      return 'Ollama'
  }
}
