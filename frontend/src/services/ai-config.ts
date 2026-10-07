/**
 * AI configuration domain — provider/model settings and the live connection test.
 */
import type {
  AiConfigTestRequest,
  AiConfigTestResult,
  AiSettings,
  PartialAiSettings,
  AiProviderEntry,
  AiProviderListResponse,
  AiProviderModelsResponse,
  AiProviderProtocol,
} from './types'
import { http } from './impl/http'

export interface AiConfigService {
  get(): Promise<AiSettings>
  update(partial: PartialAiSettings): Promise<AiSettings>
  testConfig(req: AiConfigTestRequest): Promise<AiConfigTestResult>
  /** Provider registry (B): providers as data — any compatible endpoint, zero code. */
  listProviders(): Promise<AiProviderListResponse>
  createProvider(req: {
    id: string
    displayName: string
    protocol: AiProviderProtocol
    baseUrl: string
    model: string
    apiKey?: string
  }): Promise<AiProviderEntry>
  updateProvider(id: string, patch: {
    displayName?: string
    baseUrl?: string
    model?: string
    apiKey?: string
  }): Promise<AiProviderEntry>
  deleteProvider(id: string): Promise<void>
  testProvider(id: string): Promise<AiConfigTestResult>
  activateProvider(id: string): Promise<void>
  /** Live model ids the provider's endpoint reports (vendor /models listing); empty on failure. */
  providerModels(id: string): Promise<AiProviderModelsResponse>
  /** Remote model-catalog overlay refresh (C2); never throws on failure outcomes. */
  refreshModelCatalog(): Promise<{ result: string; cachedRevision: number }>
}

export const aiConfigService: AiConfigService = {
  async get() {
    const { data } = await http.get<AiSettings>('/api/ai/config')
    return data
  },
  async update(partial) {
    const { data } = await http.put<AiSettings>('/api/ai/config', partial)
    return data
  },
  async testConfig(req) {
    const { data } = await http.post<AiConfigTestResult>('/api/ai/config/test', req)
    return data
  },
  async listProviders() {
    const { data } = await http.get<AiProviderListResponse>('/api/ai/providers')
    return data
  },
  async createProvider(req) {
    const { data } = await http.post<AiProviderEntry>('/api/ai/providers', req)
    return data
  },
  async updateProvider(id, patch) {
    const { data } = await http.put<AiProviderEntry>(`/api/ai/providers/${encodeURIComponent(id)}`, patch)
    return data
  },
  async deleteProvider(id) {
    await http.delete(`/api/ai/providers/${encodeURIComponent(id)}`)
  },
  async testProvider(id) {
    const { data } = await http.post<AiConfigTestResult>(
      `/api/ai/providers/${encodeURIComponent(id)}/test`)
    return data
  },
  async activateProvider(id) {
    await http.put(`/api/ai/providers/${encodeURIComponent(id)}/activate`)
  },
  async providerModels(id) {
    const { data } = await http.get<AiProviderModelsResponse>(
      `/api/ai/providers/${encodeURIComponent(id)}/models`)
    return data
  },
  async refreshModelCatalog() {
    const { data } = await http.post<{ result: string; cachedRevision: number }>(
      '/api/ai/providers/model-catalog/refresh')
    return data
  },
}
