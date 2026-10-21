/**
 * Connection profiles — the fill-it-in-once credential store (`/api/connections`).
 * Consumed by the Settings connections section (management) and the flow inspector
 * (per-field dropdowns for schema fields carrying `x-fengyu-connection-type`).
 */
import { http } from './impl/http'

export interface ConnectionProfile {
  id: string
  name: string
  type: string
  description?: string | null
  fields: Record<string, string>
  createdAt: string
  updatedAt: string
}

export interface ConnectionProfileDraft {
  name: string
  type: string
  description?: string | null
  fields: Record<string, string>
}

export interface ConnectionService {
  list(type?: string): Promise<ConnectionProfile[]>
  create(draft: ConnectionProfileDraft): Promise<ConnectionProfile>
  update(id: string, draft: ConnectionProfileDraft): Promise<ConnectionProfile>
  remove(id: string): Promise<{ ok: boolean }>
}

export const connectionService: ConnectionService = {
  list: (type) => http.get<ConnectionProfile[]>('/api/connections', {
    params: type ? { type } : undefined,
  }).then((r) => r.data),
  create: (draft) => http.post<ConnectionProfile>('/api/connections', draft).then((r) => r.data),
  update: (id, draft) => http.put<ConnectionProfile>(
    `/api/connections/${encodeURIComponent(id)}`, draft).then((r) => r.data),
  remove: (id) => http.delete<{ ok: boolean }>(
    `/api/connections/${encodeURIComponent(id)}`).then((r) => r.data),
}
