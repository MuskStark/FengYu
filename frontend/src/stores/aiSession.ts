import { create } from 'zustand'
import { services } from '@/services'
import type { ChatStreamError, StreamHandle } from '@/services/impl/streams'
import { i18n } from '@/i18n'
import type { MentionOption } from '@/lib/mentionSearch'
import { applyToolActivity } from '@/lib/toolActivity'
import { actOnConfirmation, parseToolConfirmation } from '@/lib/aiConfirmation'

/**
 * Conversation-centric session store (Zustand port of the Pinia aiSession). P0 scope: the
 * sidebar-facing surface — history load, selection/lazy-load, create/remove, per-conversation
 * draft + inline mentions + workspace root, and the blank-reuse rules. The streaming send
 * pipeline lands with P1 (see send.ts).
 */
export interface ChatTurn {
  id: number
  role: 'user' | 'assistant'
  content: string
  thinking: string
  streaming: boolean
  confirmations: unknown[]
  activities: import('@/lib/toolActivity').ToolActivity[]
  attachments: import('@/services/types').PersistedAttachment[]
  artifacts: import('@/services/types').ChatArtifact[]
}

export interface Conversation {
  id: number
  backendId: number | null
  title: string
  turns: ChatTurn[]
  createdAt: number
  updatedAt?: number
  loaded: boolean
  draft: string
  draftMentions: MentionOption[]
  draftAttachments: import('@/services/types').DraftAttachment[]
  scopeId: string | null
  resources: import('@/services/types').ChatResource[]
  outputTarget: string | null
  workspaceRoot: string | null
  attaching: number
  seenArtifactIds: Set<string>
  unsaved: boolean
}

interface AiSessionState {
  conversations: Conversation[]
  activeId: number | null
  busy: boolean
  error: string | null
  historyLoaded: boolean
  permissionMode: import('@/services/types').AiPermissionMode
  active: () => Conversation | null
  turns: () => ChatTurn[]
  loadHistory: () => Promise<void>
  select: (id: number) => Promise<void>
  newConversation: () => Conversation
  newChat: () => Conversation
  ensureConversation: () => Conversation
  removeConversation: (id: number) => Promise<void>
  clear: () => Promise<void>
  setWorkspace: (conv: Conversation, path: string | null) => Promise<void>
  renameConversation: (conv: Conversation, title: string) => Promise<void>
  newProjectConversation: (root: string) => Promise<Conversation>
  blankReusable: (conv: Conversation) => boolean
  send: (text: string) => Promise<void>
  attachNative: (conv: Conversation, path: string, kind: 'file' | 'directory') => void
  attachUpload: (conv: Conversation, file: File) => void
  attachUploadDirectory: (conv: Conversation, files: File[]) => void
  removeDraftAttachment: (conv: Conversation, attachmentId: string) => void
  setOutputTarget: (conv: Conversation, path: string | null) => Promise<void>
  removeResource: (conv: Conversation, resourceId: string) => void
  stop: () => void
  resolveConfirmation: (item: import('@/lib/aiConfirmation').ToolConfirmation, approve: boolean) => Promise<void>
}

let convSeq = 0
let seq = 0
let sendEpoch = 0
let handle: StreamHandle | null = null
let currentStreamId: string | null = null
let streamingConv: Conversation | null = null
let streamingTurn: ChatTurn | null = null
/** Browser File objects behind draft attachments (session memory, keyed by attachmentId). */
const browserFiles = new Map<string, File[]>()
/** Serialized save chains per conversation id (kept outside reactive state). */
const saveChains = new Map<number, Promise<void>>()

/** Map the chat stream's structured failure codes onto the Vue shell's user-facing copy. */
function streamLocalizedMessage(err: ChatStreamError): string {
  const key = err.code === 'ticket_failed' ? 'agent.streamTicketFailed'
    : err.code === 'stream_lost' ? 'agent.streamLost'
    : err.code === 'stream_ended' ? 'agent.streamEnded'
    : null
  return err.message || (key ? i18n.global.t(key) : i18n.global.t('aichat.startFailed'))
}

function newAttachmentId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `att-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/** Resolve the conversation's LIVE row by id — React state must be replaced, not mutated blind. */
function liveConv(conv: Conversation, all: Conversation[]): Conversation | null {
  return all.find(item => item.id === conv.id) ?? null
}

async function loadConversationTurns(conv: Conversation): Promise<boolean> {
  try {
    const detail = await services.chat.getConversation(conv.backendId!)
    conv.turns = detail.messages.map(message => ({
      id: ++seq,
      role: message.role === 'assistant' ? 'assistant' : 'user',
      content: message.content,
      thinking: message.thinking ?? '',
      streaming: false,
      confirmations: [],
      activities: [],
      attachments: message.attachments ?? [],
      artifacts: [],
    }))
    conv.title = detail.title
    conv.workspaceRoot = detail.workspaceRoot ?? null
    conv.updatedAt = Date.parse(detail.updatedAt) || conv.updatedAt
    conv.loaded = true
    return true
  } catch {
    // Stay unloaded (next select retries) but say so — a silent blank pane invites typing into it.
    useAiSessionStore.setState({ error: i18n.global.t('aichat.loadConversationFailed') })
    return false
  }
}

async function ensureScope(conv: Conversation): Promise<string> {
  if (conv.scopeId) return conv.scopeId
  conv.attaching++
  try {
    const scopeId = await services.chat.createChatScope()
    const live = liveConv(conv, useAiSessionStore.getState().conversations)
    if (!live) {
      void services.chat.closeChatScope(scopeId).catch(() => {/* scope never had resources */})
      throw new Error(i18n.global.t('aichat.conversationGone'))
    }
    conv.scopeId = scopeId
    return scopeId
  } finally {
    conv.attaching--
  }
}

function adoptResource(conv: Conversation, resource: import('@/services/types').ChatResource) {
  const existing = conv.resources.findIndex(item => item.resourceId === resource.resourceId)
  if (existing >= 0) conv.resources[existing] = resource
  else conv.resources.push(resource)
}

function restoreDraft(conv: Conversation, prompt: string) {
  if (!conv.draft.trim()) conv.draft = prompt
}

function rollbackOptimisticTurns(conv: Conversation, userTurn: ChatTurn, assistant: ChatTurn, prompt: string) {
  const assistantIndex = conv.turns.indexOf(assistant)
  if (assistantIndex >= 0) conv.turns.splice(assistantIndex, 1)
  const userIndex = conv.turns.indexOf(userTurn)
  if (userIndex >= 0) conv.turns.splice(userIndex, 1)
  restoreDraft(conv, prompt)
  assistant.streaming = false
}

async function recoverCommittedSend(scopeId: string, sendId: string): Promise<import('@/services/types').ChatStartResponse | null> {
  try {
    const status = await services.chat.getChatSendStatus(scopeId, sendId)
    return status.state === 'committed' && status.committedResult ? status.committedResult : null
  } catch {
    return null
  }
}

function toChatHistory(turns: ChatTurn[]): import('@/services/types').ChatMessage[] {
  return turns
    .filter(turn => !(turn.role === 'assistant' && turn.streaming))
    .map(turn => ({ role: turn.role, content: turn.content }))
}

function persistConversation(conv: Conversation) {
  const state = useAiSessionStore.getState()
  const live = liveConv(conv, state.conversations)
  if (!live) return Promise.resolve()
  const chained = (saveChains.get(live.id) ?? Promise.resolve())
    .catch(() => {})
    .then(() => doPersist(live))
  saveChains.set(live.id, chained)
  return chained
}

async function doPersist(conv: Conversation) {
  const state = useAiSessionStore.getState()
  const live = liveConv(conv, state.conversations)
  if (!live) return
  if (live.backendId == null && live.turns.length === 0) return
  if (live.backendId != null && !live.loaded) return
  live.unsaved = true
  const payload = {
    title: live.title,
    messages: live.turns.map(turn => ({
      role: turn.role,
      content: turn.content,
      thinking: turn.thinking,
      ...(turn.role === 'user' && turn.attachments.length
        ? { attachments: turn.attachments.map(a => ({ name: a.name, kind: a.kind })) }
        : {}),
    })),
  }
  try {
    if (live.backendId == null) {
      const saved = await services.chat.createConversation(payload)
      live.backendId = saved.id
    } else {
      await services.chat.updateConversation(live.backendId, payload)
    }
    live.unsaved = false
    if (live.scopeId && live.backendId != null) {
      void services.chat.bindChatScopeConversation(live.scopeId, live.backendId).catch(() => {/* best effort */})
    }
  } catch {
    live.unsaved = true
    useAiSessionStore.setState({ error: i18n.global.t('aichat.conversationSaveFailed') })
  }
}

async function syncArtifacts(conv: Conversation, turn: ChatTurn | null) {
  const live = liveConv(conv, useAiSessionStore.getState().conversations)
  if (!live?.scopeId) return
  try {
    const artifacts = await services.chat.listChatArtifacts(live.scopeId)
    if (!liveConv(conv, useAiSessionStore.getState().conversations)) return
    for (const artifact of artifacts) {
      if (live.seenArtifactIds.has(artifact.artifactId)) continue
      live.seenArtifactIds.add(artifact.artifactId)
      const target = turn ?? [...live.turns].reverse().find(item => item.role === 'assistant') ?? null
      if (target) {
        const existing = target.artifacts.find(item => item.artifactId === artifact.artifactId)
        if (existing) Object.assign(existing, artifact)
        else target.artifacts.push(artifact)
      }
    }
    useAiSessionStore.setState({ conversations: [...useAiSessionStore.getState().conversations] })
  } catch {
    /* artifact listing is best-effort */
  }
}

function emptyConversation(): Conversation {
  const conv: Conversation = {
    id: ++convSeq,
    backendId: null,
    title: '',
    turns: [],
    createdAt: Date.now(),
    updatedAt: Date.now(),
    loaded: true,
    draft: '',
    draftMentions: [],
    draftAttachments: [],
    scopeId: null,
    resources: [],
    outputTarget: null,
    workspaceRoot: null,
    attaching: 0,
    seenArtifactIds: new Set<string>(),
    unsaved: false,
  }
  return conv
}

export const useAiSessionStore = create<AiSessionState>((set, get) => ({
  conversations: [],
  activeId: null,
  busy: false,
  error: null,
  historyLoaded: false,
  permissionMode: 'ask-for-approval',

  active: () => get().conversations.find(conversation => conversation.id === get().activeId) ?? null,
  turns: () => get().active()?.turns ?? [],

  loadHistory: async () => {
    if (get().historyLoaded) return
    try {
      const list = await services.chat.listConversations()
      const present = new Set(get().conversations.map(conversation => conversation.backendId))
      const fresh: Conversation[] = list
        .filter(summary => !present.has(summary.id))
        .map(summary => ({
          ...emptyConversation(),
          id: ++convSeq,
          backendId: summary.id,
          title: summary.title,
          createdAt: Date.parse(summary.createdAt) || Date.now(),
          updatedAt: Date.parse(summary.updatedAt) || Date.parse(summary.createdAt) || Date.now(),
          loaded: false,
          workspaceRoot: summary.workspaceRoot ?? null,
        }))
      set({ conversations: [...get().conversations, ...fresh], historyLoaded: true })
    } catch {
      /* backend unreachable — StatusBar surfaces connectivity */
    }
  },

  select: async (id) => {
    const previous = get().active()
    set({ activeId: id, error: null })
    if (previous && previous.id !== id && previous.backendId != null && !previous.unsaved) {
      previous.turns = []
      previous.loaded = false
    }
    const conv = get().conversations.find(item => item.id === id)
    if (!conv || conv.loaded || conv.backendId == null) return
    try {
      const detail = await services.chat.getConversation(conv.backendId)
      conv.turns = detail.messages.map(message => ({
        id: ++seq,
        role: message.role === 'assistant' ? 'assistant' : 'user',
        content: message.content,
        thinking: message.thinking ?? '',
        streaming: false,
        confirmations: [],
        activities: [],
        attachments: message.attachments ?? [],
        artifacts: [],
      }))
      conv.title = detail.title
      conv.workspaceRoot = detail.workspaceRoot ?? null
      conv.updatedAt = Date.parse(detail.updatedAt) || conv.updatedAt
      conv.loaded = true
      set({ conversations: [...get().conversations] })
    } catch {
      set({ error: i18n.global.t('aichat.loadConversationFailed') })
    }
  },

  newConversation: () => {
    const conv = emptyConversation()
    set(state => ({ conversations: [conv, ...state.conversations], activeId: conv.id, error: null }))
    return conv
  },

  blankReusable: (conv) =>
    conv.loaded
    && conv.turns.length === 0
    && !conv.draft.trim()
    && conv.draftMentions.length === 0
    && conv.draftAttachments.length === 0
    && conv.resources.length === 0
    && conv.outputTarget === null
    && conv.workspaceRoot === null
    && conv.attaching === 0,

  newChat: () => {
    const blank = get().conversations.find(get().blankReusable)
    if (blank) {
      set({ activeId: blank.id, error: null })
      return blank
    }
    return get().newConversation()
  },

  ensureConversation: () => get().active() ?? get().newConversation(),

  removeConversation: async (id) => {
    const conv = get().conversations.find(item => item.id === id)
    set(state => ({ conversations: state.conversations.filter(item => item.id !== id) }))
    if (get().activeId === id) {
      const fallback = get().conversations[0]
      if (fallback) await get().select(fallback.id)
      else get().newConversation()
    }
    if (conv?.backendId != null) {
      try { await services.chat.deleteConversation(conv.backendId) } catch { /* best effort */ }
    }
    if (conv?.scopeId) void services.chat.closeChatScope(conv.scopeId).catch(() => {/* best effort */})
  },

  clear: async () => {
    const active = get().active()
    set({ busy: false })
    if (active) await get().removeConversation(active.id)
    set({ error: null })
    if (get().conversations.length === 0) get().newConversation()
  },

  setWorkspace: async (conv, path) => {
    conv.attaching++
    set({ conversations: [...get().conversations] })
    try {
      if (path == null) {
        if (conv.backendId != null) await services.chat.clearConversationWorkspace(conv.backendId)
        conv.workspaceRoot = null
        return
      }
      if (conv.backendId == null) {
        const saved = await services.chat.createConversation({ title: '', messages: [] })
        if (!get().conversations.some(item => item.id === conv.id)) return
        conv.backendId = saved.id
      }
      const { workspaceRoot } = await services.chat.setConversationWorkspace(conv.backendId, path)
      if (get().conversations.some(item => item.id === conv.id)) conv.workspaceRoot = workspaceRoot
    } finally {
      conv.attaching--
      set({ conversations: [...get().conversations] })
    }
  },


  renameConversation: async (conv, title) => {
    const trimmed = title.trim()
    if (!trimmed) return
    const live = liveConv(conv, get().conversations)
    if (!live) return
    if (live.backendId != null) {
      // The conversations PUT replaces the whole message list — write back what the
      // backend currently returns so a rename never clobbers history.
      const detail = await services.chat.getConversation(live.backendId)
      await services.chat.updateConversation(live.backendId, { title: trimmed, messages: detail.messages })
    }
    const after = liveConv(conv, get().conversations)
    if (after) after.title = trimmed
    set({ conversations: [...get().conversations] })
  },

  send: async (text) => {
    const prompt = text.trim()
    if (!prompt || get().busy) return
    set({ busy: true, error: null })
    const epoch = ++sendEpoch
    const conv = get().ensureConversation()
    streamingConv = conv
    streamingTurn = null
    const abandon = () => {
      if (epoch !== sendEpoch) return
      set({ busy: false })
      streamingConv = null
      streamingTurn = null
    }

    if (conv.backendId != null && !conv.loaded) {
      const loaded = await loadConversationTurns(conv)
      if (epoch !== sendEpoch || !loaded) {
        restoreDraft(conv, prompt)
        abandon()
        return
      }
    }
    if (!conv.title) conv.title = prompt.slice(0, 48)

    const sendId = newAttachmentId()
    const capturedAttachments = [...conv.draftAttachments]
    const capturedResourceIds = conv.resources
      .filter(resource => resource.status === 'ready' && resource.purpose === 'input')
      .map(resource => resource.resourceId)

    let scopeId: string | null = null
    try {
      scopeId = await ensureScope(conv)
    } catch (error) {
      set({ error: error instanceof Error && error.message ? error.message : i18n.global.t('aichat.startFailed') })
      restoreDraft(conv, prompt)
      abandon()
      return
    }
    if (epoch !== sendEpoch) {
      restoreDraft(conv, prompt)
      abandon()
      return
    }

    try {
      await services.chat.prepareChatSend(scopeId, sendId,
        capturedAttachments.filter(a => a.source === 'desktop-native' && a.displayPath)
          .map(a => ({ attachmentId: a.attachmentId, path: a.displayPath!, kind: a.kind })))
      for (const attachment of capturedAttachments.filter(a => a.source === 'browser-file')) {
        const files = browserFiles.get(attachment.attachmentId) ?? []
        if (files.length === 0) {
          throw new Error(i18n.global.t('aichat.attachmentReselect', { name: attachment.name }))
        }
        if (attachment.kind === 'directory') {
          await services.chat.uploadChatSendDirectory(scopeId, sendId, attachment.attachmentId, files)
        } else {
          await services.chat.uploadChatSendFile(scopeId, sendId, attachment.attachmentId, files[0])
        }
      }
    } catch (error) {
      set({ error: error instanceof Error && error.message ? error.message : i18n.global.t('aichat.prepareFailed') })
      void services.chat.abortChatSend(scopeId, sendId).catch(() => {/* server TTL reclaims */})
      restoreDraft(conv, prompt)
      abandon()
      return
    }
    if (epoch !== sendEpoch) {
      void services.chat.abortChatSend(scopeId, sendId).catch(() => {/* best effort */})
      restoreDraft(conv, prompt)
      abandon()
      return
    }

    const userTurn: ChatTurn = {
      id: ++seq, role: 'user', content: prompt, thinking: '', streaming: false,
      confirmations: [], activities: [],
      attachments: capturedAttachments.map(a => ({ name: a.name, kind: a.kind as 'file' | 'directory' })),
      artifacts: [],
    }
    conv.turns.push(userTurn)
    conv.updatedAt = Date.now()
    const assistant: ChatTurn = {
      id: ++seq, role: 'assistant', content: '', thinking: '', streaming: true,
      confirmations: [], activities: [], attachments: [], artifacts: [],
    }
    conv.turns.push(assistant)
    set({ conversations: [...get().conversations] })

    let response: import('@/services/types').ChatStartResponse
    try {
      response = await services.chat.send(
        toChatHistory(conv.turns), [], get().permissionMode, null, null,
        { scopeId, resourceIds: capturedResourceIds, conversationId: conv.backendId, sendId })
    } catch (error) {
      const recovered = await recoverCommittedSend(scopeId, sendId)
      if (!recovered) {
        set({ error: error instanceof Error ? error.message : i18n.global.t('aichat.startFailed') })
        rollbackOptimisticTurns(conv, userTurn, assistant, prompt)
        abandon()
        return
      }
      response = recovered
    }
    if (epoch !== sendEpoch) {
      void services.chat.cancelGeneration(response.streamId).catch(() => {/* best effort */})
      rollbackOptimisticTurns(conv, userTurn, assistant, prompt)
      abandon()
      return
    }

    currentStreamId = response.streamId
    for (const resource of response.resources ?? []) adoptResource(conv, resource)
    for (const attachment of capturedAttachments) {
      browserFiles.delete(attachment.attachmentId)
      const index = conv.draftAttachments.findIndex(d => d.attachmentId === attachment.attachmentId)
      if (index >= 0) conv.draftAttachments.splice(index, 1)
    }
    handle = services.chat.openChatStream(response.streamId, {
      onToken: (token) => { assistant.content += token; set({ conversations: [...get().conversations] }) },
      onThinking: (token) => { assistant.thinking += token; set({ conversations: [...get().conversations] }) },
      onTool: (payload) => {
        applyToolActivity(assistant.activities, payload)
        const confirmation = parseToolConfirmation(payload)
        if (confirmation) assistant.confirmations = [...assistant.confirmations, confirmation]
        set({ conversations: [...get().conversations] })
      },
      onDone: (payload) => {
        if (payload.text && !assistant.content) assistant.content = payload.text
        assistant.streaming = false
        conv.updatedAt = Date.now()
        set({ busy: false, conversations: [...get().conversations] })
        handle = null
        currentStreamId = null
        streamingConv = null
        streamingTurn = null
        void persistConversation(conv)
        void syncArtifacts(conv, assistant)
      },
      onError: (streamError: ChatStreamError) => {
        const failedStreamId = currentStreamId
        set({ error: streamLocalizedMessage(streamError) })
        assistant.streaming = false
        set({ busy: false, conversations: [...get().conversations] })
        handle = null
        currentStreamId = null
        streamingConv = null
        streamingTurn = null
        if (failedStreamId) void services.chat.cancelGeneration(failedStreamId).catch(() => {/* best effort */})
        void persistConversation(conv)
      },
    })
    set({ conversations: [...get().conversations] })
  },

  stop: () => {
    sendEpoch++
    handle?.close()
    handle = null
    const streamId = currentStreamId
    currentStreamId = null
    if (streamId) void services.chat.cancelGeneration(streamId).catch(() => {/* best effort */})
    set({ busy: false })
    const conv = streamingConv ?? get().active()
    const settled = streamingTurn
    if (settled) settled.streaming = false
    else if (conv) {
      const last = conv.turns[conv.turns.length - 1]
      if (last?.streaming) last.streaming = false
    }
    streamingConv = null
    streamingTurn = null
    if (conv && (!settled || settled.content || settled.thinking)) void persistConversation(conv)
    set({ conversations: [...get().conversations] })
  },

  resolveConfirmation: async (item, approve) => {
    await actOnConfirmation(item, approve)
    const activity = get().conversations
      .flatMap(conversation => conversation.turns.flatMap(turn => turn.activities))
      .find(value => value.id === item.toolCallId)
    if (activity && item.status === 'rejected') activity.status = 'rejected'
    if (activity && item.status === 'error') activity.status = 'failed'
    set({ conversations: [...get().conversations] })
  },

  attachNative: (conv, path, kind) => {
    conv.draftAttachments.push({
      attachmentId: newAttachmentId(),
      kind,
      name: path.split(/[\\/]/).pop() ?? path,
      source: 'desktop-native',
      displayPath: path,
      status: 'selected',
    })
    set({ conversations: [...get().conversations] })
  },

  attachUpload: (conv, file) => {
    const attachmentId = newAttachmentId()
    browserFiles.set(attachmentId, [file])
    conv.draftAttachments.push({
      attachmentId, kind: 'file', name: file.name, source: 'browser-file', status: 'selected',
    })
    set({ conversations: [...get().conversations] })
  },

  attachUploadDirectory: (conv, files) => {
    if (files.length === 0) return
    const attachmentId = newAttachmentId()
    browserFiles.set(attachmentId, files)
    conv.draftAttachments.push({
      attachmentId,
      kind: 'directory',
      name: files[0].webkitRelativePath.split('/')[0] || files[0].name,
      source: 'browser-file',
      status: 'selected',
    })
    set({ conversations: [...get().conversations] })
  },

  removeDraftAttachment: (conv, attachmentId) => {
    conv.draftAttachments = conv.draftAttachments.filter(item => item.attachmentId !== attachmentId)
    browserFiles.delete(attachmentId)
    set({ conversations: [...get().conversations] })
  },

  setOutputTarget: async (conv, path) => {
    const scopeId = await ensureScope(conv)
    const live = liveConv(conv, get().conversations)
    if (!live) {
      if (path) void services.chat.closeChatScope(scopeId).catch(() => {/* never adopted */})
      return
    }
    conv.outputTarget = await services.chat.setChatOutputTarget(scopeId, path)
    set({ conversations: [...get().conversations] })
  },

  removeResource: (conv, resourceId) => {
    conv.resources = conv.resources.filter(item => item.resourceId !== resourceId)
    set({ conversations: [...get().conversations] })
    if (conv.scopeId) void services.chat.removeChatResource(conv.scopeId, resourceId).catch(() => {/* best effort */})
  },

  newProjectConversation: async (root) => {
    const conv = get().newConversation()
    try {
      await get().setWorkspace(conv, root)
    } catch {
      set({ error: i18n.global.t('aichat.workspaceSetFailed') })
    }
    return conv
  },
}))
