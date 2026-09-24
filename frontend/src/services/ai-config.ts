/**
 * AI configuration domain — provider/model settings and the live connection test.
 */
import type { AiConfigTestRequest, AiConfigTestResult, AiSettings, PartialAiSettings } from './types'
import { http } from './impl/http'

export interface AiConfigService {
  get(): Promise<AiSettings>
  update(partial: PartialAiSettings): Promise<AiSettings>
  testConfig(req: AiConfigTestRequest): Promise<AiConfigTestResult>
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
}
