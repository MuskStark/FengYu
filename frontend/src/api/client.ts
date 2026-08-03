import axios, { type AxiosInstance } from 'axios'
import { getApiBase, getToken } from './config'
import type {
  AgentPlan,
  AgentBatchResponse,
  AgentRunDetail,
  AgentRunRequest,
  AgentRunResponse,
  AgentRunSummary,
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
  McpStatus,
  PartialAiSettings,
  PartialSettings,
  PluginDescriptor,
  PluginFileRef,
  ActiveFileEntry,
  PluginInvokeResult,
  ProcessIsolationStatus,
  SetupStatus,
  SkillDetail,
  SkillSummary,
  MarketplaceSkill,
  StoreSource,
  StoreSourceType,
  UnifiedCatalogEntry,
  InstallRecord,
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

  async uploadRuntimeDirectory(id: string, files: File[], access: 'read' | 'read-write' = 'read'): Promise<PluginFileRef> {
    const body = new FormData()
    for (const file of files) {
      body.append('files', file)
      body.append('paths', file.webkitRelativePath || file.name)
    }
    const { data } = await http.post<PluginFileRef>(
      `/api/plugin-runtime/${encodeURIComponent(id)}/files/upload-directory`, body,
      { headers: { 'Content-Type': undefined }, params: { access } },
    )
    return data
  },

  async grantRuntimeNativePath(id: string, path: string, kind: 'file' | 'directory', access: 'read' | 'write' | 'read-write'): Promise<PluginFileRef> {
    const { data } = await http.post<PluginFileRef>(`/api/plugin-runtime/${encodeURIComponent(id)}/files/native`, { path, kind, access })
    return data
  },

  async grantAiNativePath(path: string, kind: 'file' | 'directory'): Promise<ActiveFileEntry[]> {
    const { data } = await http.post<ActiveFileEntry[]>('/api/ai/files/native', {
      path,
      kind,
      writableDirectory: kind === 'directory',
    })
    return data
  },

  async uploadAiFile(file: File): Promise<ActiveFileEntry[]> {
    const body = new FormData()
    body.append('file', file)
    const { data } = await http.post<ActiveFileEntry[]>('/api/ai/files/upload', body, {
      headers: { 'Content-Type': undefined },
    })
    return data
  },

  async uploadAiDirectory(files: File[]): Promise<ActiveFileEntry[]> {
    const body = new FormData()
    for (const file of files) {
      body.append('files', file)
      body.append('paths', file.webkitRelativePath || file.name)
    }
    const { data } = await http.post<ActiveFileEntry[]>('/api/ai/files/upload-directory', body, {
      headers: { 'Content-Type': undefined },
      params: { writable: true },
    })
    return data
  },

  async revokeAiFile(pluginId: string, refId: string): Promise<void> {
    await http.post('/api/ai/files/revoke', { pluginId, refId })
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

  async aiChat(messages: ChatMessage[], activeFileRefs?: ActiveFileEntry[], permissionMode = 'ask-for-approval'): Promise<ChatStartResponse> {
    const { data } = await http.post<ChatStartResponse>('/api/ai/chat', {
      messages,
      activeFileRefs: activeFileRefs ?? [],
      permissionMode,
    })
    return data
  },

  async resolveAiToolApproval(approvalId: string, approved: boolean): Promise<PluginInvokeResult> {
    const { data } = await http.post<PluginInvokeResult>(
      `/api/ai/tool-approvals/${encodeURIComponent(approvalId)}`,
      { approved },
    )
    return data
  },

  async cancelAiGeneration(streamId: string): Promise<void> {
    await http.post('/api/ai/cancel', undefined, { params: { streamId } })
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

  /** Start 1–8 independent agent runs concurrently. */
  agentBatch: (goals: string[], config: AgentRunRequest['config']) =>
    http
      .post<AgentBatchResponse>('/api/agent/batch', { goals, config })
      .then((r) => r.data),

  /** Release the run's approval gate (plan or step); an edited plan body replaces it. */
  agentApprove: (runId: string, plan?: AgentPlan) =>
    http
      .post(`/api/agent/${encodeURIComponent(runId)}/approve`, plan)
      .then((r) => r.data),

  /** Flip the run's cancellation flag (honored cooperatively by the runner). */
  agentCancel: (runId: string) =>
    http
      .post(`/api/agent/${encodeURIComponent(runId)}/cancel`)
      .then((r) => r.data),

  /** The orchestrable tool list (name/description/inputSchema). */
  agentTools: () =>
    http.get<AgentTool[]>('/api/agent/tools').then((r) => r.data),

  /** Durable agent history and event audit trail. */
  agentRuns: () =>
    http.get<AgentRunSummary[]>('/api/agent/runs').then((r) => r.data),

  agentRunDetail: (runId: string) =>
    http
      .get<AgentRunDetail>(`/api/agent/runs/${encodeURIComponent(runId)}`)
      .then((r) => r.data),

  /** Resume only the unfinished portion of a failed/cancelled run, after plan review. */
  agentResume: (runId: string) =>
    http
      .post<AgentRunResponse>(`/api/agent/runs/${encodeURIComponent(runId)}/resume`)
      .then((r) => r.data),

  /** Read-only MCP connection and discovered-tool diagnostics. */
  mcpStatus: () =>
    http.get<McpStatus>('/api/mcp/status').then((r) => r.data),

  processIsolationStatus: () =>
    http
      .get<ProcessIsolationStatus>('/api/security/process-isolation')
      .then((r) => r.data),

  // ── Skills (Codex-style progressive disclosure, managed like plugins) ──
  /** List every discovered skill (no bodies). */
  listSkills: () =>
    http.get<SkillSummary[]>('/api/skills').then((r) => r.data),

  /** Full detail for one skill, including its markdown body. */
  getSkill: (id: string) =>
    http.get<SkillDetail>(`/api/skills/${encodeURIComponent(id)}`).then((r) => r.data),

  /** Merged marketplace view: remote catalog joined with local install state. */
  getSkillMarket: () =>
    http.get<MarketplaceSkill[]>('/api/skills/market').then((r) => r.data),

  /** Install a .fys archive uploaded as multipart form data. */
  uploadSkill: (file: File) => {
    const body = new FormData()
    body.append('file', file)
    return http.post('/api/skills/upload', body, { headers: { 'Content-Type': undefined } })
  },

  /** Install a .fys archive by absolute filesystem path (desktop shell native path). */
  uploadNativeSkill: (path: string) =>
    http.post('/api/skills/upload-native', { path }),

  /** Install a skill by id from the configured catalog. */
  installSkill: (id: string) =>
    http.post(`/api/skills/${encodeURIComponent(id)}/install`),

  /** Update an installed skill from the catalog (reuses the install path). */
  updateSkill: (id: string) =>
    http.post(`/api/skills/${encodeURIComponent(id)}/update`),

  /** Flip the .disabled marker; returns {id, enabled}. */
  setSkillEnabled: (id: string, enabled: boolean) =>
    http
      .patch<{ id: string; enabled: boolean }>(
        `/api/skills/${encodeURIComponent(id)}/enabled`,
        { enabled },
      )
      .then((r) => r.data),

  /** Uninstall an installed skill. */
  uninstallSkill: (id: string) =>
    http.delete(`/api/skills/${encodeURIComponent(id)}`),

  // ── Unified Plugin Store ─────────────────────────────────
  /** List subscribed marketplace sources. */
  getStoreSources: () =>
    http.get<StoreSource[]>('/api/plugin-store/sources').then((r) => r.data),

  /** Subscribe to a new marketplace source. */
  addStoreSource: (name: string, sourceType: StoreSourceType, catalogUrl: string) =>
    http
      .post<StoreSource>('/api/plugin-store/sources', { name, sourceType, catalogUrl })
      .then((r) => r.data),

  /** Unsubscribe a source (does not uninstall plugins). */
  deleteStoreSource: (origin: string) =>
    http.delete(`/api/plugin-store/sources/${encodeURIComponent(origin)}`),

  /** Force-refresh a source's cached catalog. */
  refreshStoreSource: (origin: string) =>
    http.post(`/api/plugin-store/sources/${encodeURIComponent(origin)}/refresh`),

  /** Aggregated catalog (optionally filtered by sourceType/category/query server-side). */
  getUnifiedCatalog: (params?: { sourceType?: StoreSourceType; category?: string; q?: string }) =>
    http
      .get<UnifiedCatalogEntry[]>('/api/plugin-store/catalog', { params })
      .then((r) => r.data),

  /** Install (or update) a plugin by uid; backend dispatches by sourceType. */
  installUnified: (uid: string) =>
    http.post(`/api/plugin-store/${encodeURIComponent(uid)}/install`),

  updateUnified: (uid: string) =>
    http.post(`/api/plugin-store/${encodeURIComponent(uid)}/update`),

  uninstallUnified: (uid: string) =>
    http.delete(`/api/plugin-store/${encodeURIComponent(uid)}`),

  setUnifiedEnabled: (uid: string, enabled: boolean) =>
    http.patch(`/api/plugin-store/${encodeURIComponent(uid)}/enabled`, { enabled }),

  /** Installation history (install records across all sources). */
  getInstallHistory: () =>
    http.get<InstallRecord[]>('/api/plugin-store/history').then((r) => r.data),
}

export type FengYuApi = typeof api
