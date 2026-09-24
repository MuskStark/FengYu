/**
 * Chat domain — AI conversations, chat-resources scopes/sends/artifacts,
 * active-file grants, and the single-shot generation stream.
 */
import type {
  ActiveFileEntry,
  ChatArtifact,
  ChatResource,
  ChatScopeRequest,
  ChatScopeSnapshot,
  ChatSendStatus,
  ChatStartResponse,
  ConversationDetail,
  ConversationPayload,
  ConversationSummary,
  FlowAuthoringContext,
  ChatMessage,
  PluginInvokeResult,
} from './types'
import { http } from './impl/http'
import { openChatStream, type ChatStreamHandlers, type StreamHandle } from './impl/streams'

export type { ChatStreamHandlers, StreamHandle }

export interface ChatService {
  send(
    messages: ChatMessage[],
    activeFileRefs?: ActiveFileEntry[],
    permissionMode?: string,
    workflowId?: string | null,
    flowContext?: FlowAuthoringContext | null,
    scope?: ChatScopeRequest,
  ): Promise<ChatStartResponse>
  cancelGeneration(streamId: string): Promise<void>
  resolveToolApproval(approvalId: string, approved: boolean): Promise<PluginInvokeResult>
  openChatStream(streamId: string, cb: ChatStreamHandlers): StreamHandle

  // ── Conversation history (persisted) ──
  listConversations(): Promise<ConversationSummary[]>
  getConversation(id: number): Promise<ConversationDetail>
  createConversation(payload: ConversationPayload): Promise<ConversationDetail>
  updateConversation(id: number, payload: ConversationPayload): Promise<ConversationDetail>
  deleteConversation(id: number): Promise<void>
  /** Attach a coding workspace root to a persisted conversation (4.1.0 coding tools). */
  setConversationWorkspace(id: number, path: string): Promise<{ workspaceRoot: string }>
  clearConversationWorkspace(id: number): Promise<void>

  // ── Chat-resources scopes ──
  createChatScope(): Promise<string>
  getChatScope(scopeId: string): Promise<ChatScopeSnapshot>
  bindChatScopeConversation(scopeId: string, conversationId: number): Promise<void>
  closeChatScope(scopeId: string): Promise<void>

  // ── Send transactions (upload-at-send) ──
  prepareChatSend(scopeId: string, sendId: string,
    attachments: Array<{ attachmentId: string; path: string; kind: 'file' | 'directory' }>): Promise<ChatSendStatus>
  uploadChatSendFile(scopeId: string, sendId: string, attachmentId: string, file: File): Promise<ChatSendStatus>
  uploadChatSendDirectory(scopeId: string, sendId: string, attachmentId: string, files: File[]): Promise<ChatSendStatus>
  getChatSendStatus(scopeId: string, sendId: string): Promise<ChatSendStatus>
  abortChatSend(scopeId: string, sendId: string): Promise<void>
  setChatOutputTarget(scopeId: string, path: string | null): Promise<string | null>

  // ── Resources & artifacts ──
  refreshChatResource(scopeId: string, resourceId: string): Promise<ChatResource>
  removeChatResource(scopeId: string, resourceId: string): Promise<void>
  listChatArtifacts(scopeId: string): Promise<ChatArtifact[]>
  saveChatArtifact(scopeId: string, artifactId: string, targetPath: string): Promise<ChatArtifact>
  downloadChatArtifact(artifactId: string): Promise<void>
  listPendingChatArtifacts(conversationId: number): Promise<ChatArtifact[]>

  // ── Active files (AI-side grants) ──
  grantAiNativePath(path: string, kind: 'file' | 'directory', writableDirectory?: boolean): Promise<ActiveFileEntry[]>
  uploadAiFile(file: File): Promise<ActiveFileEntry[]>
  uploadAiDirectory(files: File[], writable?: boolean): Promise<ActiveFileEntry[]>
  revokeAiFile(pluginId: string, refId: string): Promise<void>
}

export const chatService: ChatService = {
  async send(messages, activeFileRefs, permissionMode = 'ask-for-approval',
    workflowId, flowContext, scope) {
    const { data } = await http.post<ChatStartResponse>('/api/ai/chat', {
      messages,
      activeFileRefs: activeFileRefs ?? [],
      permissionMode,
      // Flowise-style chat binding: attaching a workflowId binds this turn to that flow
      // (draft or published) — the backend exposes it to the model as `run_current_flow`
      // inside the ordinary chat tool-call loop.
      ...(workflowId ? { workflowId } : {}),
      ...(flowContext ? { flowContext } : {}),
      ...(scope ? {
        scopeId: scope.scopeId,
        resourceIds: scope.resourceIds,
        conversationId: scope.conversationId,
        ...(scope.sendId ? { sendId: scope.sendId } : {}),
      } : {}),
    })
    return data
  },

  async cancelGeneration(streamId) {
    await http.post('/api/ai/cancel', undefined, { params: { streamId } })
  },

  async resolveToolApproval(approvalId, approved) {
    const { data } = await http.post<PluginInvokeResult>(
      `/api/ai/tool-approvals/${encodeURIComponent(approvalId)}`,
      { approved })
    return data
  },

  openChatStream: (streamId, cb) => openChatStream(streamId, cb),

  async listConversations() {
    const { data } = await http.get<ConversationSummary[]>('/api/ai/conversations')
    return data
  },
  async getConversation(id) {
    const { data } = await http.get<ConversationDetail>(`/api/ai/conversations/${id}`)
    return data
  },
  async createConversation(payload) {
    const { data } = await http.post<ConversationDetail>('/api/ai/conversations', payload)
    return data
  },
  async updateConversation(id, payload) {
    const { data } = await http.put<ConversationDetail>(`/api/ai/conversations/${id}`, payload)
    return data
  },
  async deleteConversation(id) {
    await http.delete(`/api/ai/conversations/${id}`)
  },
  async setConversationWorkspace(id, path) {
    const { data } = await http.put<{ workspaceRoot: string }>(
      `/api/ai/conversations/${id}/workspace`, { path })
    return data
  },
  async clearConversationWorkspace(id) {
    await http.delete(`/api/ai/conversations/${id}/workspace`)
  },

  async createChatScope() {
    const { data } = await http.post<{ scopeId: string }>('/api/ai/chat-resources/scopes')
    return data.scopeId
  },
  async getChatScope(scopeId) {
    const { data } = await http.get<ChatScopeSnapshot>(`/api/ai/chat-resources/${encodeURIComponent(scopeId)}`)
    return data
  },
  async bindChatScopeConversation(scopeId, conversationId) {
    await http.post(`/api/ai/chat-resources/${encodeURIComponent(scopeId)}/conversation`, { conversationId })
  },
  async closeChatScope(scopeId) {
    await http.delete(`/api/ai/chat-resources/${encodeURIComponent(scopeId)}`)
  },

  async prepareChatSend(scopeId, sendId, attachments) {
    const { data } = await http.post<ChatSendStatus>(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/sends`,
      { sendId, attachments })
    return data
  },
  async uploadChatSendFile(scopeId, sendId, attachmentId, file) {
    const body = new FormData()
    body.append('file', file)
    const { data } = await http.post<ChatSendStatus>(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/sends/${encodeURIComponent(sendId)}/uploads`,
      body, { params: { attachmentId }, headers: { 'Content-Type': undefined } })
    return data
  },
  async uploadChatSendDirectory(scopeId, sendId, attachmentId, files) {
    const body = new FormData()
    for (const file of files) {
      body.append('files', file)
      body.append('paths', file.webkitRelativePath || file.name)
    }
    const { data } = await http.post<ChatSendStatus>(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/sends/${encodeURIComponent(sendId)}/upload-directories`,
      body, { params: { attachmentId }, headers: { 'Content-Type': undefined } })
    return data
  },
  async getChatSendStatus(scopeId, sendId) {
    const { data } = await http.get<ChatSendStatus>(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/sends/${encodeURIComponent(sendId)}`)
    return data
  },
  async abortChatSend(scopeId, sendId) {
    await http.delete(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/sends/${encodeURIComponent(sendId)}`)
  },
  async setChatOutputTarget(scopeId, path) {
    const { data } = await http.post<{ outputTarget: string | null }>(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/output`, { path })
    return data.outputTarget
  },

  async refreshChatResource(scopeId, resourceId) {
    const { data } = await http.post<ChatResource>(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/resources/${encodeURIComponent(resourceId)}/refresh`)
    return data
  },
  async removeChatResource(scopeId, resourceId) {
    await http.delete(`/api/ai/chat-resources/${encodeURIComponent(scopeId)}/resources/${encodeURIComponent(resourceId)}`)
  },
  async listChatArtifacts(scopeId) {
    const { data } = await http.get<ChatArtifact[]>(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/artifacts`)
    return data
  },
  async saveChatArtifact(scopeId, artifactId, targetPath) {
    const { data } = await http.post<ChatArtifact>(
      `/api/ai/chat-resources/${encodeURIComponent(scopeId)}/artifacts/${encodeURIComponent(artifactId)}/save`,
      { targetPath })
    return data
  },
  async downloadChatArtifact(artifactId) {
    const { data } = await http.get(
      `/api/ai/chat-resources/artifacts/${encodeURIComponent(artifactId)}/download`,
      { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    const link = document.createElement('a')
    link.href = url
    link.download = 'artifact'
    link.click()
    URL.revokeObjectURL(url)
  },
  async listPendingChatArtifacts(conversationId) {
    const { data } = await http.get<ChatArtifact[]>('/api/ai/chat-resources/artifacts', {
      params: { conversationId },
    })
    return data
  },

  async grantAiNativePath(path, kind, writableDirectory = kind === 'directory') {
    const { data } = await http.post<ActiveFileEntry[]>('/api/ai/files/native', {
      path,
      kind,
      writableDirectory,
    })
    return data
  },
  async uploadAiFile(file) {
    const body = new FormData()
    body.append('file', file)
    const { data } = await http.post<ActiveFileEntry[]>('/api/ai/files/upload', body, {
      headers: { 'Content-Type': undefined },
    })
    return data
  },
  async uploadAiDirectory(files, writable = true) {
    const body = new FormData()
    for (const file of files) {
      body.append('files', file)
      body.append('paths', file.webkitRelativePath || file.name)
    }
    const { data } = await http.post<ActiveFileEntry[]>('/api/ai/files/upload-directory', body, {
      headers: { 'Content-Type': undefined },
      params: { writable },
    })
    return data
  },
  async revokeAiFile(pluginId, refId) {
    await http.post('/api/ai/files/revoke', { pluginId, refId })
  },
}
