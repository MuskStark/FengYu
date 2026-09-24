import type {
  AiConfigTestRequest,
  AiMode,
  AiProviderConfig,
  AiSettings,
  PartialAiSettings,
} from '@/services/types'

/**
 * Editable mirror of the backend's AiSettings (GET /api/ai/config).
 *
 * Field census (types.ts AiSettings — the write surface must not omit any of these):
 * - mode                → AiMode segment ('local' | 'openai' | 'anthropic' | 'deepseek') — provider section
 * - openai.endpoint / apiKey / model      → provider section (apiKeySet is GET-only, key hint)
 * - anthropic.endpoint / apiKey / model   → provider section
 * - deepseek.endpoint / apiKey / model    → provider section
 * - ollama.baseUrl / model                → provider section (local mode)
 * - temperature / topP / maxTokens / maxToolRounds / contextWindowTokens → generation section
 * - toolLoadingMode ('auto' | 'always' | 'off') + toolLoadingThreshold    → generation section
 * - systemPrompt                          → generation section
 * - activeMode / ready are GET-only status flags (rendered as a read-only chip).
 */
export interface AiFormState {
  mode: AiMode
  openai: ProviderForm
  anthropic: ProviderForm
  deepseek: ProviderForm
  ollama: { baseUrl: string; model: string }
  temperature: number
  topP: number
  maxTokens: number
  maxToolRounds: number
  contextWindowTokens: number
  toolLoadingMode: 'auto' | 'always' | 'off'
  toolLoadingThreshold: number
  systemPrompt: string
}

export interface ProviderForm {
  endpoint: string
  apiKey: string
  model: string
}

/** Cloud provider ids (the local/ollama mode has its own two-field form). */
export type CloudProviderId = 'openai' | 'anthropic' | 'deepseek'

export const CLOUD_PROVIDERS: CloudProviderId[] = ['openai', 'anthropic', 'deepseek']

export const AI_MODES: AiMode[] = ['local', 'openai', 'anthropic', 'deepseek']

/** Snapshot of the GET-only masked key (`前4***后4`); an untouched mask is not a credential. */
export const KEY_MASK = '***'

export function providerToForm(config: AiProviderConfig): ProviderForm {
  return { endpoint: config.endpoint, apiKey: config.apiKey, model: config.model }
}

export function aiFormFromSettings(settings: AiSettings): AiFormState {
  return {
    mode: settings.mode,
    openai: providerToForm(settings.openai),
    anthropic: providerToForm(settings.anthropic),
    deepseek: providerToForm(settings.deepseek),
    ollama: { baseUrl: settings.ollama.baseUrl, model: settings.ollama.model },
    temperature: settings.temperature,
    topP: settings.topP,
    maxTokens: settings.maxTokens,
    maxToolRounds: settings.maxToolRounds,
    contextWindowTokens: settings.contextWindowTokens,
    toolLoadingMode: settings.toolLoadingMode ?? 'auto',
    toolLoadingThreshold: settings.toolLoadingThreshold || 25,
    systemPrompt: settings.systemPrompt,
  }
}

/**
 * One provider's PUT payload without the apiKey when the field still holds the masked
 * snapshot from the GET — an untouched key must simply not be sent (same rule the Vue
 * shell's Settings.vue applies).
 */
function providerPayload(form: ProviderForm): { endpoint: string; model: string; apiKey?: string } {
  const payload: { endpoint: string; model: string; apiKey?: string } = {
    endpoint: form.endpoint,
    model: form.model,
  }
  if (!form.apiKey.includes(KEY_MASK)) payload.apiKey = form.apiKey
  return payload
}

/** Full PUT /api/ai/config body — persists every provider, not just the active one. */
export function aiFormToPartial(form: AiFormState): PartialAiSettings {
  return {
    mode: form.mode,
    openai: providerPayload(form.openai),
    anthropic: providerPayload(form.anthropic),
    deepseek: providerPayload(form.deepseek),
    ollama: { baseUrl: form.ollama.baseUrl, model: form.ollama.model },
    temperature: form.temperature,
    topP: form.topP,
    maxTokens: form.maxTokens,
    maxToolRounds: form.maxToolRounds,
    contextWindowTokens: form.contextWindowTokens,
    toolLoadingMode: form.toolLoadingMode,
    toolLoadingThreshold: form.toolLoadingThreshold,
    systemPrompt: form.systemPrompt,
  }
}

/** Test body for the selected mode — the same mask rule as the save payload. */
export function aiTestRequest(form: AiFormState): AiConfigTestRequest {
  if (form.mode === 'local') {
    return { mode: 'local', baseUrl: form.ollama.baseUrl, model: form.ollama.model }
  }
  const provider = form[form.mode]
  return {
    mode: form.mode,
    endpoint: provider.endpoint,
    model: provider.model,
    ...(provider.apiKey.includes(KEY_MASK) ? {} : { apiKey: provider.apiKey }),
  }
}
