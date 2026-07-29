export type ToolCategory = 'TEXT' | 'IMAGE' | 'DEV' | 'NET' | 'AI' | 'OTHER'
export type ThemeName = 'dark' | 'light'
export type LanguageName = 'en' | 'zh'
export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'OFF'

/** Declared origin of a plugin, drives the Official/Third-party card badge. */
export type PluginSource = 'OFFICIAL' | 'THIRD_PARTY'

export interface PluginDescriptor {
  id: string
  name: string
  description: string
  category: ToolCategory
  icon: string
  iconStyle: string
  version: string
  uiEntry: string
  supportsAi: boolean // NEW — drives the AI badge on the card
  source: PluginSource // NEW — drives the Official/Third-party badge
}

/** Where a runtime skill was discovered (mirrors backend Skill.Source). */
export type SkillSource = 'BUILTIN' | 'INSTALLED'

/** Summary view from GET /api/skills (no body — fetched on demand). */
export interface SkillSummary {
  id: string
  name: string
  description: string
  source: SkillSource
  enabled: boolean
}

/** Full detail from GET /api/skills/{id} (includes the markdown body). */
export interface SkillDetail extends SkillSummary {
  body: string
}

/**
 * Marketplace merged view from GET /api/skills/market — the lifecycle twin of
 * MarketplacePlugin. Combines remote-catalog metadata with local install state so the
 * Skills page can show Install / Update / Enable / Uninstall actions per entry.
 */
export interface MarketplaceSkill {
  id: string
  name: string
  description: string
  version: string
  installedVersion: string | null
  author: string | null
  icon: string | null
  homepage: string | null
  downloadUrl: string | null
  official: boolean
  installed: boolean
  enabled: boolean
  updateAvailable: boolean
}

/**
 * Backend-driven sidebar category descriptor (from GET /api/plugin-categories).
 * `id` is the lowercase category id (e.g. "dev"); `labelKey` is a vue-i18n key
 * (e.g. "category.dev"); `icon` is the sidebar glyph.
 */
export interface CategoryDescriptor {
  id: string
  labelKey: string
  icon: string
}

export interface AppSettings {
  sidebarCollapsed: boolean
  theme: ThemeName
  language: LanguageName
  logLevel: LogLevel
}

export type PartialSettings = Partial<AppSettings>

// ── AI Config ──────────────────────────────────────────────

export type AiMode = 'local' | 'openai' | 'anthropic' | 'deepseek'

export interface AiProviderConfig {
  endpoint: string
  apiKey: string // masked (前4***后4) or empty
  apiKeySet: boolean
  model: string
}

export interface AiSettings {
  mode: AiMode
  openai: AiProviderConfig
  anthropic: AiProviderConfig
  deepseek: AiProviderConfig
  ollama: { baseUrl: string; model: string }
  temperature: number
  topP: number
  maxTokens: number
  systemPrompt: string
  activeMode: AiMode
  ready: boolean
}

export type PartialAiSettings = Partial<Omit<AiSettings, 'activeMode' | 'ready'>>

export interface AiConfigTestRequest {
  mode: AiMode
  endpoint?: string
  apiKey?: string
  model?: string
  baseUrl?: string
}

export interface AiConfigTestResult {
  success: boolean
  error?: string
  warning?: string
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
}

export interface ChatStartResponse {
  streamId: string
}

export interface HealthResponse {
  status: string
}

/** Generic plugin invoke result — always JSON, shape is plugin-specific. */
export type PluginInvokeResult = Record<string, unknown>

export interface MarketplacePlugin {
  id: string
  name: string
  description: string
  version: string
  installedVersion?: string
  author?: string
  icon?: string
  category?: string
  permissions: string[]
  homepage?: string
  downloadUrl?: string
  official: boolean
  installed: boolean
  enabled: boolean
  updateAvailable: boolean
}

export interface PluginFileRef {
  id: string
  name: string
  kind: 'file' | 'directory'
  access: 'read' | 'write' | 'read-write'
  size: number
}

// ── Setup wizard (Phase 4) ──────────────────────────────────

export interface SetupStatus {
  initialized: boolean
  supportedTypes?: string[]
  embeddedTypes?: string[]
}

export interface DbTypeField {
  name: string
  label?: string
  required: boolean
  secret?: boolean
  default?: number | string
}

export interface DbTypeMeta {
  type: string
  label: string
  embedded: boolean
  fields: DbTypeField[]
}

export interface WizardParams {
  filePath?: string
  host?: string
  port?: number
  database?: string
  username?: string
  password?: string
}

export interface ConnectionTestRequest {
  type: string
  params: WizardParams
}

export interface ConnectionTestResult {
  success: boolean
  dialect?: string
  serverVersion?: string
  error?: string
}

export interface InitializeResult {
  success: boolean
  action?: string
  error?: string
  step?: string
}

// ── AI Agent (Plan-and-Execute) ─────────────────────────────

/** Approval/recovery knobs for an agent run; sent on POST /api/agent/run. */
export interface AgentRunConfig {
  requirePlanApproval: boolean
  requireStepApproval: boolean
  replanOnFailure: boolean
  maxReplans: number
}

/** POST /api/agent/run body: the user goal + optional config. */
export interface AgentRunRequest {
  goal: string
  config: AgentRunConfig
  /** Optional deterministic workflow. Omit to let the active model plan from `goal`. */
  workflow?: AgentPlan
}

/** POST /api/agent/run response: the id used to open the SSE stream. */
export interface AgentRunResponse {
  runId: string
}

export interface AgentBatchResponse {
  runIds: string[]
}

export interface AgentRunSummary {
  id: string
  goal: string
  status: string
  summary?: string | null
  error?: string | null
  resumedFrom?: string | null
  createdAt: string
  updatedAt: string
  completedAt?: string | null
}

export interface AgentStepExecution {
  index: number
  status: string
  result?: string | null
}

export interface AgentRunEvent {
  seq: number
  type: string
  data: Record<string, unknown>
  createdAt: string
}

export interface AgentRunDetail extends AgentRunSummary {
  config: AgentRunConfig
  plan?: AgentPlan | null
  executions: AgentStepExecution[]
  events: AgentRunEvent[]
}

/** One step in an agent plan. `status` mirrors the backend's free-form string. */
export interface AgentStep {
  index: number
  toolName: string
  args?: Record<string, unknown>
  description: string
  status: string
  requiresApproval?: boolean
  dependsOn?: number[]
}

/** A Plan-and-Execute plan: the goal, the ordered steps, and the planner's reasoning. */
export interface AgentPlan {
  goal: string
  steps: AgentStep[]
  reasoning: string
}

/** A Spring AI-discovered orchestrable tool (GET /api/agent/tools). */
export interface AgentTool {
  name: string
  description: string
  inputSchema: string
}

export interface McpConnectionStatus {
  name: string
  version: string
  protocolVersion: string
  initialized: boolean
}

export interface McpStatus {
  enabled: boolean
  connectionCount: number
  toolCount: number
  connections: McpConnectionStatus[]
}

export interface ProcessIsolationStatus {
  backend: string
  sandboxed: boolean
  compatibilityMode: boolean
  policy: string
}

// ── AI conversation history (persisted, GET/POST/PUT/DELETE /api/ai/conversations) ──

/** A persisted chat message as returned by the backend. */
export interface PersistedMessage {
  role: 'user' | 'assistant'
  content: string
  thinking: string
}

/** Sidebar list item — conversation without its messages. */
export interface ConversationSummary {
  id: number
  title: string
  createdAt: string
  updatedAt: string
}

/** Full conversation including its ordered message list. */
export interface ConversationDetail extends ConversationSummary {
  messages: PersistedMessage[]
}

/** Request body for create/update — title plus the full message list. */
export interface ConversationPayload {
  title: string
  messages: PersistedMessage[]
}
