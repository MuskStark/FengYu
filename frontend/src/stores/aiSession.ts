import { create } from 'zustand'
import { services } from '@/services'
import type { ChatStreamError, StreamHandle } from '@/services/impl/streams'
import { i18n } from '@/i18n'
import type { MentionOption } from '@/lib/mentionSearch'
import { applyToolActivity } from '@/lib/toolActivity'
import { actOnConfirmation, parseToolConfirmation } from '@/lib/aiConfirmation'

/**
 * Conversation-centric session store (Zustand port of the Pinia aiSession): the
 * sidebar-facing surface (history load, selection/lazy-load, create/remove, per-conversation
 * draft + inline mentions + workspace root, blank-reuse rules) plus the streaming send
 * pipeline with queued messages (same-conversation sends while a turn streams), message
 * actions (regenerate / edit-resend), context-usage tracking, and inline image attachments.
 */
export interface ChatTurn {
  id: number
  role: 'user' | 'assistant'
  content: string
  thinking: string
  /** True while reasoning fragments are still arriving in this round (transient, never
   * persisted): the thinking block follows this instead of turn.streaming so its header
   * freezes to "thought for Ns" the moment the answer/tools take over, not at turn end. */
  thinkingActive?: boolean
  streaming: boolean
  confirmations: unknown[]
  activities: import('@/lib/toolActivity').ToolActivity[]
  attachments: import('@/services/types').PersistedAttachment[]
  artifacts: import('@/services/types').ChatArtifact[]
  /** Inline vision images on user turns (session memory; persisted as name-only metadata). */
  images?: import('@/services/types').ChatInlineImage[]
}

/** A send parked while the same conversation streams. */
export interface QueuedSend {
  id: string
  /** Server-side streamId when the backend raced us and parked the turn itself. */
  streamId: string | null
  prompt: string
}

export interface ContextUsage {
  contextTokens: number
  contextWindowTokens: number
  compacted: boolean
  microcompacted: boolean
  updatedAt: number
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
  /** Sends waiting for the current turn to finish (max 3; same conversation only). */
  queue: QueuedSend[]
  /** Sidebar pin (sticky at the top of its group). @since 4.1.0 */
  pinned: boolean
  /** Archived conversations hide from the sidebar until "show archived" is on. @since 4.1.0 */
  archived: boolean
  /** Latest context-usage snapshot (the composer's context indicator). */
  usage: ContextUsage | null
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
  /**
   * Sends into one specific conversation (queued continuation targets the owning one).
   * `preserved` carries a prior turn's attachment metadata/images so regenerate re-sends
   * chips whose draft attachments were consumed by the first send.
   */
  sendTo: (conv: Conversation, text: string,
    preserved?: { attachments: ChatTurn['attachments']; images?: ChatTurn['images'] }) => Promise<void>
  /** Regenerates the last assistant answer (truncates the trailing assistant turns, resends). */
  regenerate: () => Promise<void>
  /** Truncates from a user turn and seeds the composer with its text (edit-resend). */
  editFromTurn: (conv: Conversation, turnId: number) => void
  /** Removes one queued send (discards its server-side streamId when parked). */
  removeQueuedSend: (conv: Conversation, id: string) => void
  /** Edits a queued prompt in place; parked (server-side) entries re-queue locally. */
  editQueuedSend: (conv: Conversation, id: string, text: string) => void
  setPinned: (conv: Conversation, pinned: boolean) => void
  setArchived: (conv: Conversation, archived: boolean) => void
  attachNative: (conv: Conversation, path: string, kind: 'file' | 'directory') => void
  attachUpload: (conv: Conversation, file: File) => void
  attachUploadDirectory: (conv: Conversation, files: File[]) => void
  /** Pasted/dropped images: inline vision input, base64 kept in session memory. */
  attachImages: (conv: Conversation, images: Array<{ name: string; mimeType: string; base64Data: string }>) => void
  removeDraftAttachment: (conv: Conversation, attachmentId: string) => void
  setOutputTarget: (conv: Conversation, path: string | null) => Promise<void>
  removeResource: (conv: Conversation, resourceId: string) => void
  stop: () => void
  resolveConfirmation: (item: import('@/lib/aiConfirmation').ToolConfirmation, approve: boolean, options?: {
    always?: boolean
    feedback?: string
  }) => Promise<void>
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
/** Inline image data behind 'pasted-image' draft attachments (session memory). */
const inlineImages = new Map<string, import('@/services/types').ChatInlineImage>()
/** Serialized save chains per conversation id (kept outside reactive state). */
const saveChains = new Map<number, Promise<void>>()

const MAX_QUEUE = 3
/** Inline vision images per turn — mirrors the backend's AiController cap. */
const MAX_INLINE_IMAGES = 4

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
  if (conv.draft.trim()) return
  conv.draft = prompt
  seedComposerIfActive(conv, prompt)
}

/**
 * The composer editor only re-reads conv.draft when the conversation switches — a draft
 * restored mid-conversation (rejected send, failed POST, edit-resend truncation) must be
 * pushed into the live editor explicitly, or the text sits invisible in the store.
 */
function seedComposerIfActive(conv: Conversation, text: string) {
  if (useAiSessionStore.getState().activeId !== conv.id) return
  window.dispatchEvent(new CustomEvent('fengyu:composer-seed', { detail: { text } }))
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
  // Inline vision images ride only the LATEST user message: the request re-POSTs the full
  // history every turn, and carrying every past image would balloon each request.
  const settled = turns.filter(turn => !(turn.role === 'assistant' && turn.streaming))
  let lastUserIndex = -1
  for (let i = settled.length - 1; i >= 0; i--) {
    if (settled[i].role === 'user') { lastUserIndex = i; break }
  }
  return settled.map((turn, index) =>
    index === lastUserIndex && turn.images && turn.images.length > 0
      ? { role: turn.role, content: turn.content, images: turn.images }
      : { role: turn.role, content: turn.content })
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
        ? { attachments: turn.attachments.map(a => ({ name: a.name, kind: a.kind === 'directory' ? 'directory' as const : 'file' as const })) }
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

/**
 * Drives one open SSE stream into the given assistant turn. Shared by the ordinary send
 * path and the queued-send continuation so both get identical tool/usage/terminal handling.
 */
function driveStream(conv: Conversation, assistant: ChatTurn, streamId: string): void {
  currentStreamId = streamId
  handle = services.chat.openChatStream(streamId, {
    onToken: (token) => { assistant.content += token; assistant.thinkingActive = false; setConversations() },
    onThinking: (token) => { assistant.thinking += token; assistant.thinkingActive = true; setConversations() },
    onTool: (payload) => {
      assistant.thinkingActive = false
      applyToolActivity(assistant.activities, payload)
      const confirmation = parseToolConfirmation(payload)
      if (confirmation) assistant.confirmations = [...assistant.confirmations, confirmation]
      setConversations()
    },
    onUsage: (usage) => {
      conv.usage = {
        contextTokens: usage.contextTokens,
        contextWindowTokens: usage.contextWindowTokens,
        compacted: usage.compacted,
        microcompacted: usage.microcompacted,
        updatedAt: Date.now(),
      }
      setConversations()
    },
    onDone: (payload) => {
      if (payload.text && !assistant.content) assistant.content = payload.text
      assistant.streaming = false
      assistant.thinkingActive = false
      conv.updatedAt = Date.now()
      useAiSessionStore.setState({ busy: false })
      setConversations()
      handle = null
      currentStreamId = null
      streamingConv = null
      streamingTurn = null
      void persistConversation(conv)
      void syncArtifacts(conv, assistant)
      void continueQueue(conv, payload.nextStreamId)
    },
    onError: (streamError: ChatStreamError) => {
      const failedStreamId = currentStreamId
      useAiSessionStore.setState({ error: streamLocalizedMessage(streamError) })
      assistant.streaming = false
      assistant.thinkingActive = false
      useAiSessionStore.setState({ busy: false })
      setConversations()
      handle = null
      currentStreamId = null
      streamingConv = null
      streamingTurn = null
      if (failedStreamId) void services.chat.cancelGeneration(failedStreamId).catch(() => {/* best effort */})
      // A failed turn stops the queue — parked sends (and their server turns) are discarded
      // so nothing half-contextual auto-continues.
      discardQueue(conv)
      void persistConversation(conv)
    },
  })
  setConversations()
}

function setConversations(): void {
  useAiSessionStore.setState(state => ({ conversations: [...state.conversations] }))
}

/** Drops every queued send of {@code conv} (and their parked server turns). */
function discardQueue(conv: Conversation): void {
  if (conv.queue.length === 0) return
  const parked = conv.queue.map(item => item.streamId).filter((id): id is string => id !== null)
  conv.queue = []
  setConversations()
  if (parked.length > 0) {
    void services.chat.discardQueuedSends(parked).catch(() => {/* sweep reclaims them */})
  }
}

/** After a successful turn: run the next queued send of the SAME conversation. */
async function continueQueue(conv: Conversation, nextStreamId?: string): Promise<void> {
  if (conv.queue.length === 0) return
  if (!liveConv(conv, useAiSessionStore.getState().conversations)) return
  if (nextStreamId) {
    const index = conv.queue.findIndex(item => item.streamId === nextStreamId)
    if (index >= 0) {
      const item = conv.queue[index]
      conv.queue.splice(index, 1)
      setConversations()
      await startParkedTurn(conv, item.prompt, nextStreamId)
      return
    }
    // A server-parked turn we do not track: discard it rather than orphan its stream.
    void services.chat.discardQueuedSends([nextStreamId]).catch(() => {/* best effort */})
  }
  const item = conv.queue.shift()
  if (!item) return
  setConversations()
  await useAiSessionStore.getState().sendTo(conv, item.prompt)
}

/**
 * Opens a stream the backend already parked (the race window between our local queue
 * check and the POST): the history it carries is whatever the POST carried — acceptable
 * only because this path is a rare race; the common path re-POSTs with fresh history.
 */
async function startParkedTurn(conv: Conversation, prompt: string, streamId: string): Promise<void> {
  const epoch = ++sendEpoch
  streamingConv = conv
  const userTurn: ChatTurn = {
    id: ++seq, role: 'user', content: prompt, thinking: '', streaming: false,
    confirmations: [], activities: [], attachments: [], artifacts: [],
  }
  conv.turns.push(userTurn)
  const assistant: ChatTurn = {
    id: ++seq, role: 'assistant', content: '', thinking: '', streaming: true,
    confirmations: [], activities: [], attachments: [], artifacts: [],
  }
  conv.turns.push(assistant)
  conv.updatedAt = Date.now()
  useAiSessionStore.setState({ busy: true, error: null })
  setConversations()
  if (epoch !== sendEpoch) {
    void services.chat.cancelGeneration(streamId).catch(() => {/* best effort */})
    rollbackOptimisticTurns(conv, userTurn, assistant, prompt)
    useAiSessionStore.setState({ busy: false })
    return
  }
  streamingTurn = assistant
  driveStream(conv, assistant, streamId)
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
    queue: [],
    usage: null,
    pinned: false,
    archived: false,
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
          pinned: summary.pinned === true,
          archived: summary.archivedAt != null,
        }))
      set({ conversations: [...get().conversations, ...fresh], historyLoaded: true })
    } catch {
      /* backend unreachable — StatusBar surfaces connectivity */
    }
  },

  select: async (id) => {
    const previous = get().active()
    set({ activeId: id, error: null })
    // A conversation that is streaming right now keeps its turns and loaded flag: the
    // stream's onDone persists through them, so unloading mid-stream would drop the
    // whole turn pair (the queue-continuation design streams into background
    // conversations the user has already switched away from).
    const streamingPrevious = streamingConv != null && streamingConv.id === previous?.id
    if (previous && previous.id !== id && previous.backendId != null && !previous.unsaved
        && !streamingPrevious) {
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
    if (conv) {
      // Session-memory blobs behind the chips (base64 images, browser File handles)
      // must not outlive the conversation that owned them.
      for (const item of conv.draftAttachments) {
        inlineImages.delete(item.attachmentId)
        browserFiles.delete(item.attachmentId)
      }
    }
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
    const conv = get().ensureConversation()
    await get().sendTo(conv, text)
  },

  // The streaming pipeline itself lives here (not in `send`) so queued continuation can
  // target the OWNING conversation even after the user switched views mid-stream.
  sendTo: async (conv, text, preserved) => {
    const prompt = text.trim()
    if (!prompt) return
    if (get().busy) {
      if (streamingConv && streamingConv.id === conv.id) {
        // Queued send (same conversation, text/inline-image only — file attachments need
        // their upload transaction, which belongs to a fresh send).
        if (conv.draftAttachments.some(item => item.kind !== 'image')) {
          set({ error: i18n.global.t('aichat.queueAttachmentsUnsupported') })
          restoreDraft(conv, prompt)
          return
        }
        if (conv.queue.length >= MAX_QUEUE) {
          set({ error: i18n.global.t('aichat.queueFull') })
          restoreDraft(conv, prompt)
          return
        }
        conv.queue.push({ id: newAttachmentId(), streamId: null, prompt })
        setConversations()
        return
      }
      set({ error: i18n.global.t('aichat.busyElsewhere') })
      restoreDraft(conv, prompt)
      return
    }
    set({ busy: true, error: null })
    const epoch = ++sendEpoch
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
    const capturedImages = capturedAttachments
      .filter(a => a.source === 'pasted-image')
      .flatMap(a => inlineImages.get(a.attachmentId) ?? [])
      .slice(0, MAX_INLINE_IMAGES)

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
          .map(a => ({ attachmentId: a.attachmentId, path: a.displayPath!, kind: a.kind as 'file' | 'directory' })))
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
      attachments: preserved && preserved.attachments.length > 0
        ? preserved.attachments
        : capturedAttachments.map(a => ({ name: a.name, kind: a.kind === 'directory' ? 'directory' as const : 'file' as const })),
      artifacts: [],
      ...(capturedImages.length > 0 ? { images: capturedImages }
        : preserved && preserved.images && preserved.images.length > 0 ? { images: preserved.images } : {}),
    }
    conv.turns.push(userTurn)
    conv.updatedAt = Date.now()
    const assistant: ChatTurn = {
      id: ++seq, role: 'assistant', content: '', thinking: '', streaming: true,
      confirmations: [], activities: [], attachments: [], artifacts: [],
    }
    conv.turns.push(assistant)
    setConversations()

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

    for (const resource of response.resources ?? []) adoptResource(conv, resource)
    for (const attachment of capturedAttachments) {
      if (attachment.source !== 'desktop-native' && attachment.source !== 'browser-file') continue
      browserFiles.delete(attachment.attachmentId)
      const index = conv.draftAttachments.findIndex(d => d.attachmentId === attachment.attachmentId)
      if (index >= 0) conv.draftAttachments.splice(index, 1)
    }
    // The rare race: the backend parked the POST because a stream was still closing.
    // We only POST when no local stream is open (the busy gate), and a backend stream
    // the frontend has already detached from discards its parked turns on terminal —
    // so that streamId may never be driven by a done.nextStreamId. Park it LOCALLY
    // (the queue machinery re-sends it when the slot frees) and discard the server's
    // frozen copy; the sweep reclaims it if the discard races.
    if (response.queued) {
      rollbackOptimisticTurns(conv, userTurn, assistant, prompt)
      conv.draft = ''
      // The queued chip owns the text now — clear the composer the rollback just re-seeded.
      seedComposerIfActive(conv, '')
      if (response.streamId) {
        void services.chat.discardQueuedSends([response.streamId]).catch(() => {/* sweep reclaims */})
      }
      conv.queue = [...conv.queue, { id: newAttachmentId(), streamId: null, prompt }]
      set({ busy: false })
      setConversations()
      return
    }
    streamingTurn = assistant
    driveStream(conv, assistant, response.streamId)
  },

  regenerate: async () => {
    const conv = get().active()
    if (!conv || get().busy) return
    // Drop trailing assistant turns; the last user turn is re-sent verbatim.
    while (conv.turns.length > 0 && conv.turns[conv.turns.length - 1].role === 'assistant') {
      conv.turns.pop()
    }
    const lastUser = conv.turns[conv.turns.length - 1]
    if (!lastUser || lastUser.role !== 'user') {
      set({ conversations: [...get().conversations] })
      return
    }
    conv.turns.pop()
    set({ conversations: [...get().conversations] })
    // File attachments were consumed by the first send (drafts emptied, uploads done):
    // re-send the turn's own metadata so the regenerated turn keeps its chips and images.
    await get().sendTo(conv, lastUser.content, {
      attachments: lastUser.attachments,
      images: lastUser.images,
    })
  },

  editFromTurn: (conv, turnId) => {
    if (get().busy) return
    const index = conv.turns.findIndex(turn => turn.id === turnId)
    if (index < 0) return
    const target = conv.turns[index]
    if (target.role !== 'user') return
    conv.turns.splice(index)
    if (!conv.draft.trim()) {
      conv.draft = target.content
      // Same-conversation editors never re-read conv.draft: push the truncated-away
      // text into the composer so the promised edit actually appears.
      seedComposerIfActive(conv, target.content)
    }
    set({ conversations: [...get().conversations] })
    void persistConversation(conv)
  },

  removeQueuedSend: (conv, id) => {
    const item = conv.queue.find(entry => entry.id === id)
    conv.queue = conv.queue.filter(entry => entry.id !== id)
    setConversations()
    if (item?.streamId) {
      void services.chat.discardQueuedSends([item.streamId]).catch(() => {/* sweep reclaims */})
    }
  },

  editQueuedSend: (conv, id, text) => {
    const trimmed = text.trim()
    const index = conv.queue.findIndex(entry => entry.id === id)
    if (index < 0) return
    if (!trimmed) {
      get().removeQueuedSend(conv, id)
      return
    }
    const item = conv.queue[index]
    if (item.streamId != null) {
      // A server-parked turn's history was frozen at POST time — edit by discarding it
      // and re-queueing locally so the send carries fresh context.
      void services.chat.discardQueuedSends([item.streamId]).catch(() => {/* sweep reclaims */})
      conv.queue[index] = { id: item.id, streamId: null, prompt: trimmed }
    } else {
      conv.queue[index] = { ...item, prompt: trimmed }
    }
    setConversations()
  },

  stop: () => {
    sendEpoch++
    handle?.close()
    handle = null
    const streamId = currentStreamId
    currentStreamId = null
    if (streamId) void services.chat.cancelGeneration(streamId).catch(() => {/* best effort */})
    const conv = streamingConv ?? get().active()
    const settled = streamingTurn
    set({ busy: false })
    if (settled) settled.streaming = false
    else if (conv) {
      const last = conv.turns[conv.turns.length - 1]
      if (last?.streaming) last.streaming = false
    }
    if (conv) {
      discardQueue(conv)
      if (!settled || settled.content || settled.thinking) void persistConversation(conv)
    }
    streamingConv = null
    streamingTurn = null
    setConversations()
  },

  resolveConfirmation: async (item, approve, options) => {
    await actOnConfirmation(item, approve, options)
    const activity = get().conversations
      .flatMap(conversation => conversation.turns.flatMap(turn => turn.activities))
      .find(value => value.id === item.toolCallId)
    if (activity && item.status === 'rejected') activity.status = 'rejected'
    if (activity && item.status === 'error') activity.status = 'failed'
    setConversations()
  },

  setPinned: (conv, pinned) => {
    conv.pinned = pinned
    setConversations()
    if (conv.backendId != null) {
      void services.chat.setConversationPinned(conv.backendId, pinned).catch(() => {/* keep optimistic */})
    }
  },

  setArchived: (conv, archived) => {
    conv.archived = archived
    setConversations()
    if (conv.backendId != null) {
      void services.chat.setConversationArchived(conv.backendId, archived).catch(() => {/* keep optimistic */})
    }
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
    setConversations()
  },

  attachUpload: (conv, file) => {
    const attachmentId = newAttachmentId()
    browserFiles.set(attachmentId, [file])
    conv.draftAttachments.push({
      attachmentId, kind: 'file', name: file.name, source: 'browser-file', status: 'selected',
    })
    setConversations()
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
    setConversations()
  },

  attachImages: (conv, images) => {
    if (images.length === 0) return
    const existing = conv.draftAttachments.filter(item => item.source === 'pasted-image').length
    const room = Math.max(0, MAX_INLINE_IMAGES - existing)
    if (room < images.length) {
      set({ error: i18n.global.t('aichat.imageLimit', { count: MAX_INLINE_IMAGES }) })
    }
    for (const image of images.slice(0, room)) {
      const attachmentId = newAttachmentId()
      inlineImages.set(attachmentId, image)
      conv.draftAttachments.push({
        attachmentId, kind: 'image', name: image.name, source: 'pasted-image', status: 'selected',
      })
    }
    setConversations()
  },

  removeDraftAttachment: (conv, attachmentId) => {
    conv.draftAttachments = conv.draftAttachments.filter(item => item.attachmentId !== attachmentId)
    browserFiles.delete(attachmentId)
    inlineImages.delete(attachmentId)
    setConversations()
  },

  setOutputTarget: async (conv, path) => {
    const scopeId = await ensureScope(conv)
    const live = liveConv(conv, get().conversations)
    if (!live) {
      if (path) void services.chat.closeChatScope(scopeId).catch(() => {/* never adopted */})
      return
    }
    conv.outputTarget = await services.chat.setChatOutputTarget(scopeId, path)
    setConversations()
  },

  removeResource: (conv, resourceId) => {
    conv.resources = conv.resources.filter(item => item.resourceId !== resourceId)
    setConversations()
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
