/**
 * Service layer entry — the domain surface every UI module consumes.
 *
 * Rules (see frontend/docs/service-layer.md):
 * - UI code imports from here (or the domain modules), never from axios or the
 *   legacy `@/api/client` god-object;
 * - this layer is framework-free: no react, no i18n import — the app bootstrap
 *   injects the locale via {@link configureServices};
 * - the domain method set is the complete legacy surface under cleaner names;
 *   the fixed legacy→domain mapping is pinned by parity.test.ts (the cutover
 *   safety net — no endpoint may be forgotten when `@/api/client` is deleted).
 */
import { setHttpLocaleProvider } from './impl/http'
import type { AccountService } from './account'
import type { AiConfigService } from './ai-config'
import type { AgentService } from './agent'
import type { AppUpdateService } from './app-update'
import type { ChatService } from './chat'
import type { InfiniaStoreService } from './infinia-store'
import type { McpService } from './mcp'
import type { NotificationService } from './notifications'
import type { PluginService } from './plugin'
import type { SettingsService } from './settings'
import type { SkillService } from './skill'
import type { StoreService } from './store'
import type { SystemService } from './system'
import type { WorkflowService } from './workflow'
import type { WorkspaceService } from './workspace'
import { accountService } from './account'
import { aiConfigService } from './ai-config'
import { agentService } from './agent'
import { appUpdateService } from './app-update'
import { chatService } from './chat'
import { infiniaStoreService } from './infinia-store'
import { mcpService } from './mcp'
import { notificationService } from './notifications'
import { pluginService } from './plugin'
import { settingsService } from './settings'
import { skillService } from './skill'
import { storeService } from './store'
import { systemService } from './system'
import { workflowService } from './workflow'
import { workspaceService } from './workspace'

export interface FengYuServices {
  system: SystemService
  settings: SettingsService
  aiConfig: AiConfigService
  chat: ChatService
  workspace: WorkspaceService
  agent: AgentService
  workflow: WorkflowService
  mcp: McpService
  skill: SkillService
  plugin: PluginService
  store: StoreService
  infiniaStore: InfiniaStoreService
  appUpdate: AppUpdateService
  notifications: NotificationService
  account: AccountService
}

/** The singleton every store/view imports. */
export const services: FengYuServices = {
  system: systemService,
  settings: settingsService,
  aiConfig: aiConfigService,
  chat: chatService,
  workspace: workspaceService,
  agent: agentService,
  workflow: workflowService,
  mcp: mcpService,
  skill: skillService,
  plugin: pluginService,
  store: storeService,
  infiniaStore: infiniaStoreService,
  appUpdate: appUpdateService,
  notifications: notificationService,
  account: accountService,
}

/**
 * Bootstrap hook: inject the app's i18n language so the Accept-Language header
 * keeps mirroring the settings store (e.g. `configureServices({ locale: () => i18n.language })`).
 */
export function configureServices(opts: { locale: () => string }): void {
  setHttpLocaleProvider(opts.locale)
}

export { AUTH_EXPIRED_EVENT } from './impl/http'
