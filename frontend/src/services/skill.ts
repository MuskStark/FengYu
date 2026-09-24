/**
 * Skill domain — Codex-style progressive-disclosure skills, managed like plugins.
 */
import type { MarketplaceSkill, SkillDetail, SkillSummary } from './types'
import { http } from './impl/http'

export interface SkillService {
  /** List every discovered skill (no bodies). */
  list(): Promise<SkillSummary[]>
  /** Full detail for one skill, including its markdown body. */
  get(id: string): Promise<SkillDetail>
  /** Merged marketplace view: remote catalog joined with local install state. */
  market(): Promise<MarketplaceSkill[]>
  /** Install a .fys archive uploaded as multipart form data. */
  upload(file: File): Promise<void>
  /** Install a .fys archive by absolute filesystem path (desktop shell native path). */
  uploadNative(path: string): Promise<void>
  install(id: string): Promise<void>
  update(id: string): Promise<void>
  /** Flip the .disabled marker; returns {id, enabled}. */
  setEnabled(id: string, enabled: boolean): Promise<{ id: string; enabled: boolean }>
  uninstall(id: string): Promise<void>
}

export const skillService: SkillService = {
  list: () => http.get<SkillSummary[]>('/api/skills').then((r) => r.data),
  get: (id) => http.get<SkillDetail>(`/api/skills/${encodeURIComponent(id)}`).then((r) => r.data),
  market: () => http.get<MarketplaceSkill[]>('/api/skills/market').then((r) => r.data),
  upload: (file) => {
    const body = new FormData()
    body.append('file', file)
    return http.post('/api/skills/upload', body, { headers: { 'Content-Type': undefined } })
  },
  uploadNative: (path) => http.post('/api/skills/upload-native', { path }),
  install: (id) => http.post(`/api/skills/${encodeURIComponent(id)}/install`),
  update: (id) => http.post(`/api/skills/${encodeURIComponent(id)}/update`),
  setEnabled: (id, enabled) =>
    http
      .patch<{ id: string; enabled: boolean }>(`/api/skills/${encodeURIComponent(id)}/enabled`, { enabled })
      .then((r) => r.data),
  uninstall: (id) => http.delete(`/api/skills/${encodeURIComponent(id)}`),
}
