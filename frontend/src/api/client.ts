import axios, { type AxiosInstance } from 'axios'
import { getApiBase, getToken } from './config'
import type {
  AgentPlan,
  AgentRunRequest,
  AgentRunResponse,
  AgentTool,
  AppSettings,
  CategoryDescriptor,
  ChatMessage,
  ChatStartResponse,
  ConnectionTestRequest,
  ConnectionTestResult,
  DbTypeMeta,
  HealthResponse,
  InitializeResult,
  PartialSettings,
  PluginDescriptor,
  PluginInvokeResult,
  SetupStatus,
} from './types'

const http: AxiosInstance = axios.create({
  baseURL: getApiBase(),
  headers: { 'Content-Type': 'application/json' },
})

// Attach the ZhiFlow token to every request except /api/health and /api/setup/.
// Setup calls run before a token exists (backend TokenAuthFilter bypasses /api/setup/).
http.interceptors.request.use((config) => {
  const url = config.url ?? ''
  if (!url.includes('/api/health') && !url.includes('/api/setup/')) {
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

  async getPluginCategories(): Promise<CategoryDescriptor[]> {
    const { data } = await http.get<CategoryDescriptor[]>('/api/plugin-categories')
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

export type ZhiFlowApi = typeof api
