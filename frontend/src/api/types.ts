export type ToolCategory = 'TEXT' | 'IMAGE' | 'DEV' | 'NET' | 'OTHER'
export type ThemeName = 'dark' | 'light'
export type LanguageName = 'en' | 'zh'

export interface PluginDescriptor {
  id: string
  name: string
  description: string
  category: ToolCategory
  icon: string
  iconStyle: string
  version: string
  uiEntry: string
}

export interface AppSettings {
  sidebarCollapsed: boolean
  theme: ThemeName
  language: LanguageName
}

export type PartialSettings = Partial<AppSettings>

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
