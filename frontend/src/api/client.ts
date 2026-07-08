import axios, { type AxiosInstance } from 'axios'
import { getApiBase, getToken } from './config'
import type {
  AppSettings,
  ChatMessage,
  ChatStartResponse,
  HealthResponse,
  PartialSettings,
  PluginDescriptor,
  PluginInvokeResult,
} from './types'

const http: AxiosInstance = axios.create({
  baseURL: getApiBase(),
  headers: { 'Content-Type': 'application/json' },
})

// Attach the ZhiFlow token to every request except /api/health.
http.interceptors.request.use((config) => {
  const url = config.url ?? ''
  if (!url.includes('/api/health')) {
    const token = getToken()
    if (token) {
      config.headers.set('X-ZhiFlow-Token', token)
    }
  }
  return config
})

export const api = {
  async health(): Promise<HealthResponse> {
    const { data } = await http.get<HealthResponse>('/api/health')
    return data
  },

  async getPlugins(): Promise<PluginDescriptor[]> {
    const { data } = await http.get<PluginDescriptor[]>('/api/plugins')
    return data
  },

  async getSettings(): Promise<AppSettings> {
    const { data } = await http.get<AppSettings>('/api/settings')
    return data
  },

  async putSettings(partial: PartialSettings): Promise<AppSettings> {
    const { data } = await http.put<AppSettings>('/api/settings', partial)
    return data
  },

  async pluginInvoke(
    id: string,
    action: string,
    args: Record<string, unknown> = {},
  ): Promise<PluginInvokeResult> {
    const { data } = await http.post<PluginInvokeResult>(
      `/api/plugins/${encodeURIComponent(id)}/invoke`,
      { action, args },
    )
    return data
  },

  async aiChat(messages: ChatMessage[]): Promise<ChatStartResponse> {
    const { data } = await http.post<ChatStartResponse>('/api/ai/chat', {
      messages,
    })
    return data
  },
}

export type ZhiFlowApi = typeof api
