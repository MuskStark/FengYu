/**
 * Workspace domain — read-only coding-workspace browsing for a persisted
 * conversation (tree + single file preview). 4.1 coding-agent hot zone.
 */
import type { WorkspaceFilePreview, WorkspaceTree } from './types'
import { http } from './impl/http'

export interface WorkspaceService {
  tree(conversationId: number): Promise<WorkspaceTree>
  file(conversationId: number, path: string): Promise<WorkspaceFilePreview>
}

export const workspaceService: WorkspaceService = {
  async tree(conversationId) {
    const { data } = await http.get<WorkspaceTree>(
      `/api/ai/conversations/${conversationId}/workspace/tree`)
    return data
  },
  async file(conversationId, path) {
    const { data } = await http.get<WorkspaceFilePreview>(
      `/api/ai/conversations/${conversationId}/workspace/file`, { params: { path } })
    return data
  },
}
