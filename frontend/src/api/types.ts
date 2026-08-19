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
  author?: string | null
  enabled?: boolean
  /** Permissions declared in the package manifest (e.g. "files.read"). Populated by the runtime descriptor endpoint. */
  permissions?: string[]
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

/** Screen-control (computer use) capability probe; null when the desktop-mode bean is absent. */
export interface ComputerUseStatus {
  available: boolean
  reason?: string | null
}

export interface PermissionRuleTable {
  allow: string[]
  ask: string[]
  deny: string[]
}

export interface AppSettings {
  sidebarCollapsed: boolean
  theme: ThemeName
  language: LanguageName
  logLevel: LogLevel
  unsandboxedPlugins: boolean
  updateApiBase: string
  computerUseEnabled: boolean
  computerUse?: ComputerUseStatus | null
  memoryEnabled?: boolean
  marketplaceRequireChecksum?: boolean
  permissionRules?: PermissionRuleTable | Record<string, unknown>
  invalidPermissionRules?: string[]
  hooks?: string
}


export type PartialSettings = Partial<AppSettings>

// ── AI Config ──────────────────────────────────────────────

export type AiMode = 'local' | 'openai' | 'anthropic' | 'deepseek'
export type AiPermissionMode = 'ask-for-approval' | 'approve-for-me' | 'full-access'

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
  maxToolRounds: number
  contextWindowTokens: number
  systemPrompt: string
  activeMode: AiMode
  ready: boolean
}

/**
 * Partial PUT body for /api/ai/config — every key is optional and the backend persists only
 * the keys present. Provider sub-objects are partial too (the write side never sends the
 * GET-only `apiKeySet` flag).
 */
export type PartialAiSettings = Partial<Omit<AiSettings, 'activeMode' | 'ready' | 'openai' | 'anthropic' | 'deepseek'>> & {
  openai?: Partial<AiProviderConfig>
  anthropic?: Partial<AiProviderConfig>
  deepseek?: Partial<AiProviderConfig>
}

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
  /** Includes grants discovered from absolute paths typed in the latest user message. */
  activeFileRefs?: ActiveFileEntry[]
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

/** Pre-install view of an incoming .fyp package (POST /api/plugin-market/inspect[-native]). */
export interface PackageInspection {
  id: string
  name: string
  version: string
  installed: boolean
  installedVersion?: string | null
  /** Version step vs the installed copy; null when the id is not installed. */
  comparison: 'upgrade' | 'downgrade' | 'same' | null
}

// ── Unified Plugin Store (FengYu + Claude + Codex) ──
export type StoreSourceType = 'FENGYU' | 'CLAUDE' | 'CODEX'

export interface StoreSource {
  origin: string
  sourceType: StoreSourceType
  catalogUrl: string
  name: string
}

export interface StoreAuthor {
  name: string
  email?: string | null
  url?: string | null
}

export interface StoreInterfaceMeta {
  displayName?: string
  shortDescription?: string
  longDescription?: string
  developerName?: string
  category?: string
  capabilities?: string[]
  websiteURL?: string
  brandColor?: string
  logo?: string
  screenshots?: string[]
  defaultPrompt?: string[]
}

export interface UnifiedCatalogEntry {
  uid: string
  origin: string
  sourceType: StoreSourceType
  name: string
  displayName: string
  description: string
  author: StoreAuthor | null
  category: string | null
  keywords: string[]
  homepage: string | null
  pinnedSha: string | null
  declaredSkills: string[]
  mcpServers: string[]
  interfaceMeta: StoreInterfaceMeta | null
  installed: boolean
  installedVersion: string | null
  updateAvailable: boolean
  enabled: boolean
}

export interface InstallRecord {
  uid: string
  pluginName: string
  sourceType: StoreSourceType
  origin: string
  version: string | null
  pinnedSha: string | null
  hasMcpServers: boolean
  enabled: boolean
  /** Declared skill paths (parsed from the install record's JSON-string column; empty until installed). */
  declaredSkills: string[]
  /** MCP server config file references (parsed from the install record's JSON-string column). */
  mcpServerRefs: string[]
  installedAt: string
  updatedAt: string
}

export interface PluginFileRef {
  id: string
  name: string
  kind: 'file' | 'directory'
  access: 'read' | 'write' | 'read-write'
  size: number
}

/** A file grant active for one AI chat turn, scoped to a plugin whose tool may consume it. */
export interface ActiveFileEntry {
  pluginId: string
  ref: PluginFileRef
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
  adminUsername?: string
  adminPassword?: string
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
  permissionMode: AiPermissionMode
}

/** One file-class workflow input resolved into per-plugin grants for a run. */
export interface AgentRunFile {
  name: string
  /** Pass-through grants minted earlier via POST /api/ai/files/*. */
  refs?: ActiveFileEntry[]
  /** Grants a native path at run start (advanced escape hatch when no picker is available). */
  nativePath?: string
  kind?: 'file' | 'directory'
  writableDirectory?: boolean
  /** Mints a host-managed cross-plugin scratch directory (no user interaction). */
  createSharedDirectory?: boolean
}

/** POST /api/agent/run body: the user goal + optional config. */
export interface AgentRunRequest {
  goal: string
  config: AgentRunConfig
  /** Optional deterministic workflow. Omit to let the active model plan from `goal`. */
  workflow?: AgentPlan
  /** File-class workflow inputs, keyed by input name; args carry `@file:<name>` placeholders. */
  files?: AgentRunFile[]
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

export interface AgentTaskSummary {
  taskId: string
  kind: string
  description: string
  status: string
  createdAt: string
  output: string
  cancelRequested: boolean
}

export interface AgentScheduleSummary {
  scheduleId: string
  workflowId: string
  intervalSeconds: number
  recurring: boolean
  nextFireAt: string
  fires: number
  lastTaskId?: string | null
  lastError?: string | null
  expiresAt: string
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
  /** Canvas-authored fixed result; when set the runner skips the tool call. */
  pinnedResult?: string | null
}

/** A Plan-and-Execute plan: the goal, the ordered steps, and the planner's reasoning. */
export interface AgentPlan {
  goal: string
  steps: AgentStep[]
  reasoning: string
}

/** Canvas position of one node, keyed by compiled step index (persisted layout). */
export interface WorkflowNodeLayout {
  x: number
  y: number
}

/**
 * The persisted canvas graph (Flowise's flowData equivalent): the exact nodes and
 * edges the author arranged. Tool nodes reference tools by name; the builder
 * rehydrates full descriptors from the live tool catalog. `plan` + `layout`
 * remain the compiled execution contract; `graph` is the round-trip source of
 * truth for the editor.
 */
export interface FlowGraphNode {
  id: string
  type: string
  position: { x: number; y: number }
  data?: Record<string, unknown>
}

export interface FlowGraphEdge {
  id: string
  source: string
  target: string
}

export interface FlowGraph {
  nodes: FlowGraphNode[]
  edges: FlowGraphEdge[]
}

/** A reusable workflow definition. Published definitions are also exposed as AI tools. */
export interface WorkflowDefinition {
  id: string
  name: string
  description: string
  inputSchema: Record<string, unknown>
  plan: AgentPlan
  layout?: Record<string, WorkflowNodeLayout> | null
  graph?: FlowGraph | null
  published: boolean
  revision: number
  createdAt: string
  updatedAt: string
}

export interface WorkflowDraft {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  plan: AgentPlan
  layout?: Record<string, WorkflowNodeLayout> | null
  graph?: FlowGraph | null
}

export interface WorkflowRunRequest {
  inputs: Record<string, unknown>
  config: AgentRunConfig
  files?: AgentRunFile[]
}

/** Catalog option source: a plugin list method the host fetches options from. */
export interface FlowNodeOptionSource {
  method: string
  /** Field of the result holding the option list (e.g. "accounts"). */
  items?: string
  value: string
  label: string
  labelSecondary?: string
  multiple?: boolean
}

/** How one named dataset is extracted from a context method's result. */
export interface FlowNodeContextFeed {
  /** Result field holding the list (e.g. "sheets"). */
  list: string
  /** Flat feed: field of each entry to extract (e.g. "name"). */
  item?: string
  /** Keyed feed: field of each entry to group by (e.g. sheet "name"). */
  key?: string
  /** Keyed feed: nested list field of each entry (e.g. "columns"). */
  items?: string
  /** Keyed feed: field within each nested item to extract (e.g. "header"). */
  itemField?: string
}

/**
 * Context source: options derived at edit time from ANOTHER input's current
 * value (e.g. the workbook path → sheet/column datasets via the analyze RPC).
 */
export interface FlowNodeContext {
  method: string
  /** Call params; "{{value}}" templates this input's current value. */
  params?: Record<string, string>
  /** "node" → the host mints a canvas-<nodeId> session (default). */
  sessionScope?: 'node'
  feeds: Record<string, FlowNodeContextFeed>
}

/** Reference to a dataset produced by a context source on another input. */
export interface FlowNodeOptionsFromContext {
  set: string
  /** Row field whose current value selects the keyed bucket (e.g. sheetName). */
  keyedBy?: string
}

/**
 * Flow-canvas data types (descriptor v2). `any` is the default when a declaration
 * omits `type` — it connects everywhere without type checking, keeping v1
 * declarations fully compatible.
 */
export type FlowValueType = 'string' | 'number' | 'boolean' | 'object' | 'array' | 'file' | 'any'

/** Nested field shape of an object/array output (descriptor v2). */
export interface FlowOutputProperty {
  type?: FlowValueType | 'integer'
  title?: string
  description?: string
  examples?: unknown[]
  properties?: Record<string, FlowOutputProperty>
  items?: FlowOutputProperty
}

/** One declared input of a flow node (widget-driven, explicit canvas config). */
export interface FlowNodeInput {
  name: string
  widget: 'text' | 'number' | 'switch' | 'select' | 'textarea' | 'json' | 'analyze' | 'rows'
  title?: string
  description?: string
  /** Expected value type (descriptor v2); drives the variable picker's type filter. */
  type?: FlowValueType
  /** Required on the canvas in addition to the tool schema's required list (v2). */
  required?: boolean
  /** Input placeholder (v2). */
  placeholder?: string
  /** Example values; the first doubles as the manual-editor placeholder (v2). */
  examples?: unknown[]
  /** One-line field-level hint (v2). */
  help?: string
  /** Fold into Advanced settings (v2). */
  advanced?: boolean
  options?: string[]
  default?: unknown
  optionsFrom?: 'workbook-sheets' | 'workbook-columns'
  source?: FlowNodeOptionSource
  context?: FlowNodeContext
  optionsFromContext?: FlowNodeOptionsFromContext
  fields?: Array<{
    name: string
    widget: 'text' | 'number' | 'switch' | 'select'
    title?: string
    optionsFrom?: 'workbook-sheets' | 'workbook-columns'
    optionsFromContext?: FlowNodeOptionsFromContext
  }>
}

/** One named output port of a flow node. */
export interface FlowNodeOutput {
  name: string
  title?: string
  type?: FlowValueType
  description?: string
  /** Usage hint shown in the output viewer (v2). */
  help?: string
  /** Example values shown until a real run provides data (v2). */
  examples?: unknown[]
  /** Nested fields of an object output — the variable tree renders them recursively (v2). */
  properties?: Record<string, FlowOutputProperty>
  /** Element shape of an array output (v2). */
  items?: FlowOutputProperty
}

/**
 * Explicit flow-canvas node declaration (plugin manifest `flowNodes` or the host's
 * flow-nodes/builtin.json). The builder renders node inputs/outputs from this
 * configuration; execution still targets the bound aiTool by name.
 */
export interface FlowNodeDescriptor {
  tool: string
  label?: string
  /** action (default) executes a tool; control/start are canvas-authored structural kinds (v2). */
  kind?: 'action' | 'control' | 'start'
  /** Node-level help shown in the inspector's help drawer (v2). */
  help?: string
  docsUrl?: string
  color?: string
  icon?: string
  inputs?: FlowNodeInput[]
  outputs?: FlowNodeOutput[]
}

/** A Spring AI-discovered orchestrable tool (GET /api/agent/tools). */
export interface AgentTool {
  id: string
  /** Explicit canvas node declaration; nodes exist on the canvas only when present. */
  flowNode?: FlowNodeDescriptor | null
  pluginId?: string | null
  name: string
  /** English description — the one sent to the LLM. */
  description: string
  /**
   * Locale-localized description for frontend display only (resolved server-side from the plugin
   * manifest's i18n block). Falls back to {@link description} when the plugin ships no translation
   * for the current locale or this is a built-in / MCP tool without manifest metadata.
   */
  localizedDescription?: string | null
  inputSchema: string
  outputSchema?: string | null
  revision: string
}

export interface McpConnectionStatus {
  name: string
  version: string
  protocolVersion: string
  initialized: boolean
}

export interface McpStatus {
  enabled: boolean
  /** Present on backends that expose runtime MCP server management endpoints. */
  dynamicManagement?: boolean
  connectionCount: number
  toolCount: number
  connections: McpConnectionStatus[]
}

export type McpTransportType = 'STDIO' | 'SSE' | 'STREAMABLE_HTTP'

export interface McpServer {
  id: string
  name: string
  type: McpTransportType
  command?: string | null
  args: string[]
  url?: string | null
  endpoint?: string | null
  enabled: boolean
  status: 'connected' | 'disconnected' | 'error' | string
  error?: string | null
  serverVersion: string
  protocolVersion: string
  tools: string[]
  envKeys: string[]
  headerNames: string[]
}

export interface McpServerRequest {
  name: string
  type: McpTransportType
  command?: string
  args?: string[]
  env?: Record<string, string>
  url?: string
  endpoint?: string
  headers?: Record<string, string>
  enabled?: boolean
}

export interface McpPrompt {
  name: string
  title: string
  description: string
  arguments: string[]
}

export interface McpResource {
  name: string
  title: string
  uri: string
  description: string
  mimeType: string
}

export interface McpCallResult {
  isError: boolean
  content: unknown[]
}

export interface ProcessIsolationStatus {
  backend: string
  sandboxed: boolean
  reduced: boolean
  compatibilityMode: boolean
  lifecycleIsolation: string
  policy: string
}

/** Result of POST /api/plugin-db/provision/{id} or /api/plugin-db/status/{id}. */
export interface PluginDbProvisionResult {
  provisioned: boolean
  status: string
  pluginId: string
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

// ── Application update check (GET /api/updates/check) ───────────────────────

/**
 * Latest-release probe shared by every deployment mode. `portableMode` tells the UI whether
 * the running backend can self-swap its JAR (portable java -jar) or whether the Electron shell
 * owns the install via electron-updater. `downloadAssetUrl` is the Infinia.jar asset URL used
 * only by the portable self-update path; null when the asset is absent.
 */
export interface UpdateCheckResult {
  currentVersion: string
  latestVersion: string
  updateAvailable: boolean
  releaseUrl: string
  releaseName: string
  publishedAt: string
  prerelease: boolean
  releaseNotes: string
  portableMode: boolean
  downloadAssetUrl: string | null
}

/** Result of POST /api/updates/apply (portable self-update only). */
export interface UpdateApplyResult {
  success: boolean
  action: string
}

// ── Host-side unified notifications (/api/notifications) ────────────────────

/** Severity levels mirrored from the backend's NotificationService validation set. */
export type NotificationLevel = 'info' | 'success' | 'warning' | 'error'

/**
 * One host notification from GET/POST /api/notifications or the live SSE
 * `notification` event. `source` names the originator ("host" | "agent" |
 * "plugin:<id>") — the shell localizes titles for known sources and displays
 * the stored title otherwise.
 */
export interface AppNotification {
  id: number
  source: string
  level: NotificationLevel
  title: string
  body: string
  link: string | null
  read: boolean
  createdAt: string
  readAt: string | null
}

/** POST /api/notifications body (used by the plugin notify host bridge). */
export interface CreateNotificationPayload {
  source: string
  level: NotificationLevel
  title: string
  body?: string
  link?: string
}
