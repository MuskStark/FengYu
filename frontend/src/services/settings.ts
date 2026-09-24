/**
 * Settings domain — app settings plus the permission-rule table and workspace
 * hooks (both live under /api/settings).
 */
import type { AppSettings, PartialSettings, PermissionRuleTable } from './types'
import { http } from './impl/http'

export interface SettingsService {
  get(): Promise<AppSettings>
  update(partial: PartialSettings): Promise<AppSettings>
  putPermissionRules(rules: PermissionRuleTable): Promise<{ ok: boolean; rules: number }>
  /** `json` is the raw hooks document string — sent with an explicit JSON content type. */
  putHooks(json: string): Promise<{ ok: boolean; hooks: number }>
}

export const settingsService: SettingsService = {
  async get() {
    const { data } = await http.get<AppSettings>('/api/settings')
    return data
  },
  async update(partial) {
    const { data } = await http.put<AppSettings>('/api/settings', partial)
    return data
  },
  putPermissionRules: (rules) =>
    http.put<{ ok: boolean; rules: number }>('/api/settings/permission-rules', rules).then((r) => r.data),
  putHooks: (json) =>
    http.put<{ ok: boolean; hooks: number }>('/api/settings/hooks', json, {
      headers: { 'Content-Type': 'application/json' },
    }).then((r) => r.data),
}
