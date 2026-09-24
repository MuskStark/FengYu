/**
 * Workflow domain — flow definitions (draft/publish/revisions), runs, and the
 * agent-facing webhook triggers.
 */
import type {
  AgentRunResponse,
  PermissionRuleTable,
  WorkflowDefinition,
  WorkflowDraft,
  WorkflowRevisionSummary,
  WorkflowRunRequest,
  WorkflowWebhookDeliverySummary,
  WorkflowWebhookTriggerCreated,
  WorkflowWebhookTriggerSummary,
} from './types'
import { http } from './impl/http'

export interface WorkflowService {
  list(): Promise<WorkflowDefinition[]>
  get(workflowId: string): Promise<WorkflowDefinition>
  create(draft: WorkflowDraft): Promise<WorkflowDefinition>
  update(workflowId: string, draft: WorkflowDraft): Promise<WorkflowDefinition>
  publish(workflowId: string, published: boolean, expectedRevision?: number): Promise<WorkflowDefinition>
  revisions(workflowId: string): Promise<WorkflowRevisionSummary[]>
  revision(workflowId: string, revision: number): Promise<WorkflowDefinition>
  restoreRevision(workflowId: string, revision: number, expectedRevision?: number): Promise<WorkflowDefinition>
  delete(workflowId: string): Promise<unknown>
  run(workflowId: string, request: WorkflowRunRequest): Promise<AgentRunResponse>

  webhookTriggers(): Promise<WorkflowWebhookTriggerSummary[]>
  webhookDeliveries(triggerId: string, limit?: number): Promise<WorkflowWebhookDeliverySummary[]>
  createWebhookTrigger(request: {
    workflowId: string
    name?: string
    defaultInputs?: Record<string, unknown>
    permissionMode?: string
  }): Promise<WorkflowWebhookTriggerCreated>
  rotateWebhookSecret(triggerId: string): Promise<WorkflowWebhookTriggerCreated>
  deleteWebhookTrigger(triggerId: string): Promise<{ ok: boolean }>
}

export const workflowService: WorkflowService = {
  list: () => http.get<WorkflowDefinition[]>('/api/workflows').then((r) => r.data),
  get: (workflowId) =>
    http.get<WorkflowDefinition>(`/api/workflows/${encodeURIComponent(workflowId)}`).then((r) => r.data),
  create: (draft) => http.post<WorkflowDefinition>('/api/workflows', draft).then((r) => r.data),
  update: (workflowId, draft) =>
    http.put<WorkflowDefinition>(`/api/workflows/${encodeURIComponent(workflowId)}`, draft).then((r) => r.data),
  publish: (workflowId, published, expectedRevision) =>
    http.post<WorkflowDefinition>(`/api/workflows/${encodeURIComponent(workflowId)}/publish`,
      { published, expectedRevision }).then((r) => r.data),
  revisions: (workflowId) =>
    http.get<WorkflowRevisionSummary[]>(`/api/workflows/${encodeURIComponent(workflowId)}/revisions`).then((r) => r.data),
  revision: (workflowId, revision) =>
    http.get<WorkflowDefinition>(`/api/workflows/${encodeURIComponent(workflowId)}/revisions/${revision}`).then((r) => r.data),
  restoreRevision: (workflowId, revision, expectedRevision) =>
    http.post<WorkflowDefinition>(
      `/api/workflows/${encodeURIComponent(workflowId)}/revisions/${revision}/restore`,
      { expectedRevision }).then((r) => r.data),
  delete: (workflowId) =>
    http.delete(`/api/workflows/${encodeURIComponent(workflowId)}`).then((r) => r.data),
  run: (workflowId, request) =>
    http.post<AgentRunResponse>(`/api/workflows/${encodeURIComponent(workflowId)}/run`, request).then((r) => r.data),

  webhookTriggers: () =>
    http.get<WorkflowWebhookTriggerSummary[]>('/api/agent/webhook-triggers').then((r) => r.data),
  webhookDeliveries: (triggerId, limit = 20) =>
    http.get<WorkflowWebhookDeliverySummary[]>(
      `/api/agent/webhook-triggers/${encodeURIComponent(triggerId)}/deliveries?limit=${limit}`)
      .then((r) => r.data),
  createWebhookTrigger: (request) =>
    http.post<WorkflowWebhookTriggerCreated>('/api/agent/webhook-triggers', request).then((r) => r.data),
  rotateWebhookSecret: (triggerId) =>
    http.post<WorkflowWebhookTriggerCreated>(
      `/api/agent/webhook-triggers/${encodeURIComponent(triggerId)}/rotate-secret`).then((r) => r.data),
  deleteWebhookTrigger: (triggerId) =>
    http.delete<{ ok: boolean }>(`/api/agent/webhook-triggers/${encodeURIComponent(triggerId)}`).then((r) => r.data),
}

// Re-exported to keep the settings-domain sibling import surface explicit
// (the legacy api exposed putPermissionRules next to workflows in one object).
export type { PermissionRuleTable }
