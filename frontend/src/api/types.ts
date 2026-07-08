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
