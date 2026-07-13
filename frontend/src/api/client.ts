import axios, { type AxiosInstance } from 'axios'
import { getApiBase, getToken } from './config'
import type {
  AgentPlan,
  AgentRunRequest,
  AgentRunResponse,
  AgentTool,
  AiConfigTestRequest,
  AiConfigTestResult,
  AiSettings,
  AppSettings,
  CategoryDescriptor,
  ChatMessage,
  ChatStartResponse,
  ConnectionTestRequest,
  ConnectionTestResult,
  ConversationDetail,
  ConversationPayload,
  ConversationSummary,
  DbTypeMeta,
  HealthResponse,
  InitializeResult,
  MarketplacePlugin,
  PartialAiSettings,
  PartialSettings,
  PluginDescriptor,
  PluginFileRef,
  PluginInvokeResult,
  SetupStatus,
} from './types'

const http: AxiosInstance = axios.create({
  baseURL: getApiBase(),
  headers: { 'Content-Type': 'application/json' },
})

// Attach the FengYu token to every request except /api/health and /api/setup/.
// Setup calls run before a token exists (backend TokenAuthFilter bypasses /api/setup/).
http.interceptors.request.use((config) => {
  const url = config.url ?? ''
  if (!url.includes('/api/health') && !url.includes('/api/setup/')) {
    const token = getToken()
    if (token) {
      config.headers.set('X-FengYu-Token', token)
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
    const { data } = await http.get<PluginDescriptor[]>('/api/plugin-runtime')
    return data
  },

  async getPluginCategories(): Promise<CategoryDescriptor[]> {
    const { data } = await http.get<CategoryDescriptor[]>('/api/plugin-categories')
    return data
  },

  async getMarketplacePlugins(): Promise<MarketplacePlugin[]> {
    const { data } = await http.get<MarketplacePlugin[]>('/api/plugin-market')
    return data
  },

  async uploadPlugin(file: File): Promise<void> {
    const body = new FormData()
    body.append('file', file)
    await http.post('/api/plugin-market/upload', body, {
      headers: { 'Content-Type': undefined },
    })
  },

  async uploadNativePlugin(path: string): Promise<void> {
    await http.post('/api/plugin-market/upload-native', { path })
  },

  async installPlugin(id: string): Promise<void> {
    await http.post(`/api/plugin-market/${encodeURIComponent(id)}/install`)
  },

  async updatePlugin(id: string): Promise<void> {
    await http.post(`/api/plugin-market/${encodeURIComponent(id)}/update`)
  },

  async setPluginEnabled(id: string, enabled: boolean): Promise<void> {
    await http.patch(`/api/plugin-market/${encodeURIComponent(id)}/enabled`, { enabled })
  },

  async uploadRuntimeFile(id: string, file: File): Promise<PluginFileRef> {
    const body = new FormData()
    body.append('file', file)
    const { data } = await http.post<PluginFileRef>(`/api/plugin-runtime/${encodeURIComponent(id)}/files/upload`, body, {
      headers: { 'Content-Type': undefined },
    })
    return data
  },

  async grantRuntimeNativePath(id: string, path: string, kind: 'file' | 'directory', access: 'read' | 'write' | 'read-write'): Promise<PluginFileRef> {
    const { data } = await http.post<PluginFileRef>(`/api/plugin-runtime/${encodeURIComponent(id)}/files/native`, { path, kind, access })
    return data
  },

  async createRuntimeOutput(id: string): Promise<PluginFileRef> {
    const { data } = await http.post<PluginFileRef>(`/api/plugin-runtime/${encodeURIComponent(id)}/files/output`)
    return data
  },

  async exportRuntimeOutput(id: string, ref: string): Promise<void> {
    const { data } = await http.get(`/api/plugin-runtime/${encodeURIComponent(id)}/files/export/${encodeURIComponent(ref)}`, { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    const link = document.createElement('a')
    link.href = url; link.download = 'plugin-output.zip'; link.click()
    URL.revokeObjectURL(url)
  },

  async uninstallPlugin(id: string): Promise<void> {
    await http.delete(`/api/plugin-market/${encodeURIComponent(id)}`)
  },

  async getSettings(): Promise<AppSettings> {
    const { data } = await http.get<AppSettings>('/api/settings')
    return data
  },

  async putSettings(partial: PartialSettings): Promise<AppSettings> {
    const { data } = await http.put<AppSettings>('/api/settings', partial)
    return data
  },

  // ── AI Config ───────────────────────────────────────────────
  async getAiSettings(): Promise<AiSettings> {
    const { data } = await http.get<AiSettings>('/api/ai/config')
    return data
  },

  async putAiSettings(partial: PartialAiSettings): Promise<AiSettings> {
    const { data } = await http.put<AiSettings>('/api/ai/config', partial)
    return data
  },

  async testAiConnection(req: AiConfigTestRequest): Promise<AiConfigTestResult> {
    const { data } = await http.post<AiConfigTestResult>('/api/ai/config/test', req)
    return data
  },

  async pluginInvoke(
    id: string,
    action: string,
    args: Record<string, unknown> = {},
  ): Promise<PluginInvokeResult> {
    const { data } = await http.post<PluginInvokeResult>(
      `/api/plugin-runtime/${encodeURIComponent(id)}/invoke`,
      { method: action, params: args },
    )
    return data
  },

  async aiChat(messages: ChatMessage[]): Promise<ChatStartResponse> {
    const { data } = await http.post<ChatStartResponse>('/api/ai/chat', {
      messages,
    })
    return data
  },

  // ── AI conversation history (persisted) ──────────────────────
  async listConversations(): Promise<ConversationSummary[]> {
    const { data } = await http.get<ConversationSummary[]>('/api/ai/conversations')
    return data
  },

  async getConversation(id: number): Promise<ConversationDetail> {
    const { data } = await http.get<ConversationDetail>(`/api/ai/conversations/${id}`)
    return data
  },

  async createConversation(payload: ConversationPayload): Promise<ConversationDetail> {
    const { data } = await http.post<ConversationDetail>('/api/ai/conversations', payload)
    return data
  },

  async updateConversation(id: number, payload: ConversationPayload): Promise<ConversationDetail> {
    const { data } = await http.put<ConversationDetail>(`/api/ai/conversations/${id}`, payload)
    return data
  },

  async deleteConversation(id: number): Promise<void> {
    await http.delete(`/api/ai/conversations/${id}`)
  },

  async getSetupStatus(): Promise<SetupStatus> {
    const { data } = await http.get<SetupStatus>('/api/setup/status')
    return data
  },

  async getSetupTypes(): Promise<DbTypeMeta[]> {
    const { data } = await http.get<DbTypeMeta[]>('/api/setup/types')
    return data
  },

  async testConnection(req: ConnectionTestRequest): Promise<ConnectionTestResult> {
    const { data } = await http.post<ConnectionTestResult>(
      '/api/setup/test-connection',
      req,
    )
    return data
  },

  async initializeSetup(req: ConnectionTestRequest): Promise<InitializeResult> {
    const { data } = await http.post<InitializeResult>(
      '/api/setup/initialize',
      req,
    )
    return data
  },

  // ── AI Agent (Plan-and-Execute, Task 16/20) ───────────────────────
  /** Start an agent run; returns {runId}. Open GET /api/agent/stream to observe. */
  agentRun: (req: AgentRunRequest) =>
    http.post<AgentRunResponse>('/api/agent/run', req).then((r) => r.data),

  /** Release the run's approval gate (plan or step); an edited plan body replaces it. */
  agentApprove: (runId: string, plan?: AgentPlan) =>
    http
      .post(`/api/agent/${encodeURIComponent(runId)}/approve`, { plan })
      .then((r) => r.data),

  /** Flip the run's cancellation flag (honored cooperatively by the runner). */
  agentCancel: (runId: string) =>
    http
      .post(`/api/agent/${encodeURIComponent(runId)}/cancel`)
      .then((r) => r.data),

  /** The orchestrable tool list (name/description/inputSchema). */
  agentTools: () =>
    http.get<AgentTool[]>('/api/agent/tools').then((r) => r.data),
}

export type FengYuApi = typeof api
