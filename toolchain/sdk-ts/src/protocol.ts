/**
 * Canonical iframe <-> host protocol contract.
 *
 * This module is deliberately free of browser side effects so the host shell, the
 * development simulator, and plugin UIs can all consume the same constants and
 * message types.
 */
export const PROTOCOL_VERSION = '3.0.0' as const

export const PLUGIN_MESSAGE_SOURCE = 'fengyu-plugin' as const
export const HOST_MESSAGE_SOURCE = 'fengyu-host' as const

export const HOST_METHODS = {
  ready: 'host.ready',
  invoke: 'rpc.invoke',
  notify: 'notify',
  filesOpen: 'files.open',
  filesInputDirectory: 'files.inputDirectory',
  filesWorkspaceDirectory: 'files.workspaceDirectory',
  filesOutputDirectory: 'files.outputDirectory',
  filesExport: 'files.export',
} as const

export type HostMethod = typeof HOST_METHODS[keyof typeof HOST_METHODS]
export type Theme = 'dark' | 'light'

export interface HostEnvironment {
  protocolVersion: string
  /** Id of the plugin the host loaded into this iframe. */
  pluginId: string
  /** Version declared in the loaded plugin's manifest. */
  pluginVersion: string
  /** Permissions granted to the plugin by the host runtime (e.g. "files.read"). */
  permissions: string[]
  theme: Theme
  locale: string
  platform: 'web' | 'desktop'
  capabilities: HostMethod[]
}

export interface HostError {
  code: 'ABORTED' | 'CANCELLED' | 'INCOMPATIBLE_PROTOCOL' | 'INVALID_REQUEST' | 'PERMISSION_DENIED' | 'TIMEOUT' | 'HOST_ERROR'
  message: string
  details?: unknown
}

export interface PluginRequestMessage {
  source: typeof PLUGIN_MESSAGE_SOURCE
  type: 'request'
  protocolVersion: typeof PROTOCOL_VERSION
  id: string
  method: HostMethod
  params?: Record<string, unknown>
}

export interface PluginCancelMessage {
  source: typeof PLUGIN_MESSAGE_SOURCE
  type: 'cancel'
  protocolVersion: typeof PROTOCOL_VERSION
  id: string
}

export type PluginMessage = PluginRequestMessage | PluginCancelMessage

export interface HostResponseMessage {
  source: typeof HOST_MESSAGE_SOURCE
  type: 'response'
  protocolVersion: typeof PROTOCOL_VERSION
  id: string
  result?: unknown
  error?: HostError
}

export interface HostEventMessage {
  source: typeof HOST_MESSAGE_SOURCE
  type: 'event'
  protocolVersion: typeof PROTOCOL_VERSION
  event: 'environment'
  data: Partial<HostEnvironment>
}

export type HostMessage = HostResponseMessage | HostEventMessage

export const HOST_CAPABILITIES: HostMethod[] = Object.values(HOST_METHODS)

export function isPluginMessage(value: unknown): value is PluginMessage {
  if (!value || typeof value !== 'object') return false
  const message = value as Partial<PluginMessage>
  return message.source === PLUGIN_MESSAGE_SOURCE
    && (message.type === 'request' || message.type === 'cancel')
    && message.protocolVersion === PROTOCOL_VERSION
    && typeof message.id === 'string'
}

export function isHostMessage(value: unknown): value is HostMessage {
  if (!value || typeof value !== 'object') return false
  const message = value as Partial<HostMessage>
  return message.source === HOST_MESSAGE_SOURCE
    && (message.type === 'response' || message.type === 'event')
    && message.protocolVersion === PROTOCOL_VERSION
}

export function hostError(error: unknown, code: HostError['code'] = 'HOST_ERROR'): HostError {
  return {
    code,
    message: error instanceof Error ? error.message : String(error),
  }
}
