/**
 * Plugin domain — runtime descriptors, package management, DB provisioning,
 * file grants, and RPC invocation. The plugin host view (iframe postMessage
 * bridge) and the AI tool registry both consume this single surface.
 */
import type {
  ActiveFileEntry,
  CategoryDescriptor,
  PackageInspection,
  PluginDbProvisionResult,
  PluginDescriptor,
  PluginFileRef,
  PluginInvokeResult,
  PluginRuntimeStatus,
} from './types'
import { http } from './impl/http'

export interface PluginService {
  list(): Promise<PluginDescriptor[]>
  uiTicket(id: string): Promise<string>
  runtimeStatuses(): Promise<PluginRuntimeStatus[]>
  runtimeStatus(id: string): Promise<PluginRuntimeStatus>
  categories(): Promise<CategoryDescriptor[]>

  uploadPackage(file: File, confirmPermissions?: boolean): Promise<void>
  uploadNativePackage(path: string, confirmPermissions?: boolean): Promise<void>
  /** Read an incoming .fyp's manifest WITHOUT installing — powers the update-confirm dialog. */
  inspect(file: File): Promise<PackageInspection>
  /** Path-based twin of inspect for the desktop shell's native file picker. */
  inspectNative(path: string): Promise<PackageInspection>

  provisionDb(id: string): Promise<PluginDbProvisionResult>
  dbStatus(id: string): Promise<PluginDbProvisionResult>

  uploadFile(id: string, file: File): Promise<PluginFileRef>
  uploadDirectory(id: string, files: File[], access?: 'read' | 'read-write'): Promise<PluginFileRef>
  grantNativePath(id: string, path: string, kind: 'file' | 'directory', access: 'read' | 'write' | 'read-write'): Promise<PluginFileRef>
  createOutput(id: string): Promise<PluginFileRef>
  exportOutput(id: string, ref: string): Promise<void>

  invoke(
    id: string,
    action: string,
    args?: Record<string, unknown>,
    options?: { callId: string; signal?: AbortSignal },
  ): Promise<PluginInvokeResult>
  cancelInvoke(id: string, callId: string): Promise<void>
  /** Invokes one plugin RPC method directly (run-form dynamic option sources). */
  invokeMethod<T = Record<string, unknown>>(pluginId: string, method: string, params?: Record<string, unknown>): Promise<T>
}

export const pluginService: PluginService = {
  async list() {
    const { data } = await http.get<PluginDescriptor[]>('/api/plugin-runtime')
    return data
  },
  async uiTicket(id) {
    const { data } = await http.post<{ ticket: string }>(`/api/plugin-runtime/${encodeURIComponent(id)}/ui-ticket`)
    return data.ticket
  },
  async runtimeStatuses() {
    const { data } = await http.get<PluginRuntimeStatus[]>('/api/plugin-runtime/status')
    return data
  },
  async runtimeStatus(id) {
    const { data } = await http.get<PluginRuntimeStatus>(`/api/plugin-runtime/${encodeURIComponent(id)}/status`)
    return data
  },
  async categories() {
    const { data } = await http.get<CategoryDescriptor[]>('/api/plugin-categories')
    return data
  },

  async uploadPackage(file, confirmPermissions = false) {
    const body = new FormData()
    body.append('file', file)
    await http.post('/api/plugin-packages/upload', body, {
      params: { confirmPermissions },
      headers: { 'Content-Type': undefined },
    })
  },
  async uploadNativePackage(path, confirmPermissions = false) {
    await http.post('/api/plugin-packages/upload-native', { path, confirmPermissions })
  },
  async inspect(file) {
    const body = new FormData()
    body.append('file', file)
    const { data } = await http.post<PackageInspection>('/api/plugin-packages/inspect', body, {
      headers: { 'Content-Type': undefined },
    })
    return data
  },
  async inspectNative(path) {
    const { data } = await http.post<PackageInspection>('/api/plugin-packages/inspect-native', { path })
    return data
  },

  async provisionDb(id) {
    const { data } = await http.post<PluginDbProvisionResult>(`/api/plugin-db/provision/${encodeURIComponent(id)}`)
    return data
  },
  async dbStatus(id) {
    const { data } = await http.post<PluginDbProvisionResult>(`/api/plugin-db/status/${encodeURIComponent(id)}`)
    return data
  },

  async uploadFile(id, file) {
    const body = new FormData()
    body.append('file', file)
    const { data } = await http.post<PluginFileRef>(`/api/plugin-runtime/${encodeURIComponent(id)}/files/upload`, body, {
      headers: { 'Content-Type': undefined },
    })
    return data
  },
  async uploadDirectory(id, files, access = 'read') {
    const body = new FormData()
    for (const file of files) {
      body.append('files', file)
      body.append('paths', file.webkitRelativePath || file.name)
    }
    const { data } = await http.post<PluginFileRef>(
      `/api/plugin-runtime/${encodeURIComponent(id)}/files/upload-directory`, body,
      { headers: { 'Content-Type': undefined }, params: { access } })
    return data
  },
  async grantNativePath(id, path, kind, access) {
    const { data } = await http.post<PluginFileRef>(`/api/plugin-runtime/${encodeURIComponent(id)}/files/native`, { path, kind, access })
    return data
  },
  async createOutput(id) {
    const { data } = await http.post<PluginFileRef>(`/api/plugin-runtime/${encodeURIComponent(id)}/files/output`)
    return data
  },
  async exportOutput(id, ref) {
    const { data } = await http.get(`/api/plugin-runtime/${encodeURIComponent(id)}/files/export/${encodeURIComponent(ref)}`, { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    const link = document.createElement('a')
    link.href = url
    link.download = 'plugin-output.zip'
    link.click()
    URL.revokeObjectURL(url)
  },

  async invoke(id, action, args = {}, options) {
    const { data } = await http.post<PluginInvokeResult>(
      `/api/plugin-runtime/${encodeURIComponent(id)}/invoke`,
      { callId: options?.callId, method: action, params: args },
      { signal: options?.signal })
    return data
  },
  async cancelInvoke(id, callId) {
    await http.post(`/api/plugin-runtime/${encodeURIComponent(id)}/invoke/${encodeURIComponent(callId)}/cancel`)
  },
  async invokeMethod<T = Record<string, unknown>>(pluginId: string, method: string, params: Record<string, unknown> = {}) {
    const { data } = await http.post<T>(`/api/plugin-runtime/${encodeURIComponent(pluginId)}/invoke`, {
      callId: `ui_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`,
      method,
      params,
    })
    return data
  },
}

// Re-exported for the plugin host view's native-grant path (kept the original
// api.grantAiNativePath shape; see chat.grantAiNativePath for the AI-side twin).
export type { ActiveFileEntry }
