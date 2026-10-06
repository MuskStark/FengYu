/**
 * Workspace domain — read-only coding-workspace browsing for a persisted
 * conversation (tree + single file preview). 4.1 coding-agent hot zone.
 */
import type { WorkspaceFilePreview, WorkspaceTree } from './types'
import { http } from './impl/http'

export interface WorkspaceService {
  tree(conversationId: number): Promise<WorkspaceTree>
  file(conversationId: number, path: string): Promise<WorkspaceFilePreview>
  /**
   * Raw bytes of one workspace image as an object URL (the panel's preview pane).
   * The CALLER owns the URL — revoke it when the preview moves on.
   */
  rawImage(conversationId: number, path: string): Promise<string>
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
  async rawImage(conversationId, path) {
    const { data } = await http.get<Blob>(
      `/api/ai/conversations/${conversationId}/workspace/raw-image`,
      { params: { path }, responseType: 'blob' })
    return URL.createObjectURL(data)
  },
}
