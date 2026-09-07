import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import { openAiStream, type SseHandle } from '@/api/sse'
import type { ActiveFileEntry, AiPermissionMode, ChatMessage, ConversationPayload, PluginDescriptor, PluginFileRef } from '@/api/types'
import { actOnConfirmation, parseToolConfirmation, type ToolConfirmation } from './aiConfirmation'
import { applyToolActivity, type ToolActivity } from './aiToolActivity'

export interface ChatTurn {
  id: number
  role: 'user' | 'assistant'
  content: string
  thinking: string
  streaming: boolean
  confirmations: ToolConfirmation[]
  activities: ToolActivity[]
}

export interface Conversation {
  /** Local UI id (stable across the session, used for v-for keys). */
  id: number
  /** Backend DB id; null until the conversation has been persisted. */
  backendId: number | null
  title: string
  turns: ChatTurn[]
  createdAt: number
  /** Whether messages have been fetched from the backend (lazy-loaded on select). */
  loaded: boolean
}

/**
 * Conversation-centric AI session store with backend persistence.
 *
 * History lives in the DB (via /api/ai/conversations) so it survives refresh/restart. The store
 * mirrors it in memory: summaries load on mount, a conversation's messages lazy-load when it is
 * first opened, and switching away releases its turns again (only the summaries stay resident, so
 * a long-lived desktop shell does not accumulate every conversation's transcript). Each completed
 * assistant turn is persisted (create on first save, update thereafter). Streaming writes into
 * its own conversation's assistant turn via a reactive() proxy so token deltas repaint live —
 * including when the user switches to another conversation mid-stream.
 */
export const useAiSessionStore = defineStore('aiSession', () => {
  const conversations = ref<Conversation[]>([])
  const activeId = ref<number | null>(null)
  const busy = ref(false)
  const error = ref<string | null>(null)
  const historyLoaded = ref(false)
  const permissionMode = ref<AiPermissionMode>('ask-for-approval')
  let seq = 0
  let convSeq = 0
  let handle: SseHandle | null = null
  let currentStreamId: string | null = null
  // The conversation/turn an in-flight stream writes into. The user may switch to
  // another conversation mid-stream (the streaming closures bind these directly, so
  // tokens keep landing in the right turn regardless of what is on screen) — stop()
  // and the lazy unload below must therefore target the streaming conversation, not
  // whatever happens to be active when they run.
  let streamingConv: Conversation | null = null
  let streamingTurn: ChatTurn | null = null

  const activeFiles = ref<ActiveFileEntry[]>([])

  /**
   * Cached installed-plugin descriptors. Used by AiChat to populate the plugin picker shown before a
   * file/dir grant (the grant is plugin-scoped, so the user must choose a plugin first). Loaded
   * lazily when the user opens the attach affordance.
   */
  const installedPlugins = ref<PluginDescriptor[]>([])
  async function loadInstalledPlugins() {
    installedPlugins.value = await api.getPlugins()
  }

  function addActiveFile(pluginId: string, ref: PluginFileRef) {
    const idx = activeFiles.value.findIndex(
      (f) => f.pluginId === pluginId && f.ref.name === ref.name,
    )
    if (idx >= 0) {
      const previous = activeFiles.value[idx]
      if (previous.ref.id !== ref.id) {
        void api.revokeAiFile(previous.pluginId, previous.ref.id).catch(() => {/* best effort */})
      }
      activeFiles.value[idx] = { pluginId, ref }
    }
    else activeFiles.value.push({ pluginId, ref })
  }

  function removeActiveFile(pluginId: string, refId: string) {
    void api.revokeAiFile(pluginId, refId).catch(() => {/* best effort */})
    activeFiles.value = activeFiles.value.filter(
      (f) => !(f.pluginId === pluginId && f.ref.id === refId),
    )
  }

  function clearActiveFiles() {
    for (const entry of activeFiles.value) {
      void api.revokeAiFile(entry.pluginId, entry.ref.id).catch(() => {/* best effort */})
    }
    activeFiles.value = []
  }

  /** Active files with a chosen plugin — the ones actually sent with the chat request. */
  function sendableFileRefs(): ActiveFileEntry[] {
    return activeFiles.value.filter((f) => f.pluginId.trim() !== '')
  }

  const active = computed<Conversation | null>(
    () => conversations.value.find((c) => c.id === activeId.value) ?? null,
  )
  const turns = computed<ChatTurn[]>(() => active.value?.turns ?? [])

  function newConversation(): Conversation {
    const conv: Conversation = {
      id: ++convSeq,
      backendId: null,
      title: '',
      turns: [],
      createdAt: Date.now(),
      loaded: true, // brand-new, nothing to fetch
    }
    conversations.value.unshift(conv)
    activeId.value = conv.id
    error.value = null
    // While a stream runs, the active files belong to its in-flight request — revoking
    // them mid-stream could break tool calls still reading those grants, so a switch
    // made during busy keeps them attached (they clear on the next non-busy switch).
    if (!busy.value) clearActiveFiles()
    return conv
  }

  /**
   * Sidebar "New chat": reuse an existing empty conversation instead of minting a new
   * one on every click — repeated clicks used to pile up untitled blank rows. A
   * conversation with zero turns is indistinguishable from fresh, so the first one
   * found (newest first) is simply re-activated.
   */
  function newChat(): Conversation {
    const empty = conversations.value.find((c) => c.turns.length === 0)
    if (empty) {
      if (activeId.value !== empty.id && !busy.value) clearActiveFiles()
      activeId.value = empty.id
      error.value = null
      return empty
    }
    return newConversation()
  }

  function ensureActive(): Conversation {
    return active.value ?? newConversation()
  }

  /** Load the sidebar summaries (no messages yet). Called once on shell mount. */
  async function loadHistory() {
    if (historyLoaded.value) return
    try {
      const list = await api.listConversations()
      conversations.value = list.map((s) => ({
        id: ++convSeq,
        backendId: s.id,
        title: s.title,
        turns: [],
        createdAt: Date.parse(s.createdAt) || Date.now(),
        loaded: false,
      }))
      historyLoaded.value = true
    } catch {
      // Backend unreachable — keep whatever is in memory; StatusBar surfaces connectivity.
    }
  }

  /**
   * Select a conversation, lazy-loading its messages from the backend on first open.
   * Switching while a stream runs is allowed: the stream's callbacks close over their
   * own conversation/turn, so generation continues into the backgrounded conversation
   * and the switch only changes what is on screen.
   */
  async function select(id: number) {
    const previous = active.value
    if (activeId.value !== id && !busy.value) clearActiveFiles()
    activeId.value = id
    error.value = null
    if (previous && previous.id !== id) unloadTurns(previous)
    const conv = conversations.value.find((c) => c.id === id)
    if (!conv || conv.loaded || conv.backendId == null) return
    try {
      const detail = await api.getConversation(conv.backendId)
      conv.turns = detail.messages.map((m) => ({
        id: ++seq,
        role: m.role,
        content: m.content,
        thinking: m.thinking,
        streaming: false,
        confirmations: [], activities: [],
      }))
      conv.title = detail.title
      conv.loaded = true
    } catch {
      // leave unloaded; a retry on next select will try again
    }
  }

  /**
   * Release a switched-away conversation's turns so memory does not grow without
   * bound in the long-lived desktop shell. The summary row stays in the sidebar;
   * reopen re-fetches the messages from the backend. Never-persisted conversations
   * (backendId null) hold the only copy of their turns, and the conversation an
   * in-flight stream is still writing into must keep its reactive turn.
   */
  function unloadTurns(conv: Conversation) {
    if (conv.backendId == null) return
    if (busy.value && conv === streamingConv) return
    conv.turns = []
    conv.loaded = false
  }

  async function removeConversation(id: number) {
    const conv = conversations.value.find((c) => c.id === id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    if (activeId.value === id) activeId.value = conversations.value[0]?.id ?? null
    if (conv?.backendId != null) {
      try {
        await api.deleteConversation(conv.backendId)
      } catch {
        /* best effort — the row stays but the UI already dropped it */
      }
    }
  }

  function toPayload(conv: Conversation): ConversationPayload {
    return {
      title: conv.title,
      messages: conv.turns.map((t) => ({
        role: t.role,
        content: t.content,
        thinking: t.thinking,
      })),
    }
  }

  /** Persist a conversation: create on first save, update afterward. */
  async function persist(conv: Conversation) {
    try {
      if (conv.backendId == null) {
        const saved = await api.createConversation(toPayload(conv))
        conv.backendId = saved.id
      } else {
        await api.updateConversation(conv.backendId, toPayload(conv))
      }
    } catch {
      // Non-fatal: the turn is still shown in memory; a later turn will retry the save.
    }
  }

  async function send(text: string) {
    const prompt = text.trim()
    if (!prompt || busy.value) return
    error.value = null

    const conv = ensureActive()
    if (!conv.title) conv.title = prompt.slice(0, 48)

    conv.turns.push({ id: ++seq, role: 'user', content: prompt, thinking: '', streaming: false, confirmations: [], activities: [] })
    // reactive() so streaming closures mutate the proxy (live repaint), not the raw object.
    const assistant = reactive<ChatTurn>({
      id: ++seq,
      role: 'assistant',
      content: '',
      thinking: '',
      streaming: true,
      confirmations: [],
      activities: [],
    })
    conv.turns.push(assistant)
    busy.value = true
    streamingConv = conv
    streamingTurn = assistant

    try {
      const { streamId, activeFileRefs: resolvedRefs = [] } = await api.aiChat(
        toChatHistory(conv.turns), sendableFileRefs(), permissionMode.value,
      )
      currentStreamId = streamId
      // The backend turns absolute paths explicitly typed in the latest user message into normal
      // plugin-scoped grants. Keep them active so follow-up turns such as "continue" retain access.
      for (const entry of resolvedRefs) addActiveFile(entry.pluginId, entry.ref)
      handle = openAiStream(streamId, {
        onToken: (t) => {
          assistant.content += t
        },
        onThinking: (t) => {
          assistant.thinking += t
        },
        onTool: (payload) => {
          applyToolActivity(assistant.activities, payload)
          const confirmation = parseToolConfirmation(payload)
          if (confirmation) assistant.confirmations.push(confirmation)
        },
        onDone: (payload) => {
          if (payload.text && !assistant.content) assistant.content = payload.text
          assistant.streaming = false
          busy.value = false
          handle = null
          currentStreamId = null
          streamingConv = null
          streamingTurn = null
          void persist(conv) // save the completed turn
        },
        onError: (message) => {
          const failedStreamId = currentStreamId
          error.value = message
          assistant.streaming = false
          busy.value = false
          handle = null
          currentStreamId = null
          streamingConv = null
          streamingTurn = null
          if (failedStreamId) void api.cancelAiGeneration(failedStreamId).catch(() => {/* best effort */})
          void persist(conv)
        },
      })
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to start chat'
      // The request never opened a stream — drop the placeholder assistant turn instead of
      // leaving an empty bubble in the transcript.
      const placeholder = conv.turns.indexOf(assistant)
      if (placeholder >= 0) conv.turns.splice(placeholder, 1)
      assistant.streaming = false
      busy.value = false
      streamingConv = null
      streamingTurn = null
    }
  }

  function stop() {
    handle?.close()
    handle = null
    const streamId = currentStreamId
    currentStreamId = null
    if (streamId) void api.cancelAiGeneration(streamId).catch(() => {/* best effort */})
    busy.value = false
    // The stream may target a conversation the user switched away from; settle its
    // placeholder turn and persist the conversation that owns it, not the active one.
    const conv = streamingConv ?? active.value
    if (streamingTurn) streamingTurn.streaming = false
    else {
      const t = active.value?.turns
      const last = t?.[t.length - 1]
      if (last && last.streaming) last.streaming = false
    }
    streamingConv = null
    streamingTurn = null
    if (conv) void persist(conv)
  }

  async function resolveConfirmation(item: ToolConfirmation, approve: boolean) {
    await actOnConfirmation(item, approve)
    // The confirmation belongs to the turn that produced it, which may not be the active
    // conversation (approvals surfaced in the composer can be resolved after switching).
    // Search every conversation so the originating activity row leaves its "waiting" state.
    const activity = conversations.value
      .flatMap(conv => conv.turns.flatMap(turn => turn.activities))
      .find(value => value.id === item.toolCallId)
    if (activity && item.status === 'rejected') activity.status = 'rejected'
    if (activity && item.status === 'error') activity.status = 'failed'
  }

  /** Delete the active conversation (backend + local) and start a fresh one. */
  async function clear() {
    clearActiveFiles()
    stop()
    const cur = active.value
    if (cur) await removeConversation(cur.id)
    error.value = null
    if (conversations.value.length === 0) newConversation()
  }

  return {
    conversations,
    activeId,
    active,
    turns,
    busy,
    error,
    historyLoaded,
    newConversation,
    newChat,
    loadHistory,
    select,
    removeConversation,
    send,
    stop,
    resolveConfirmation,
    clear,
    activeFiles,
    addActiveFile,
    removeActiveFile,
    clearActiveFiles,
    sendableFileRefs,
    installedPlugins,
    loadInstalledPlugins,
    permissionMode,
  }
})

/**
 * Convert rendered turns to provider history. The live assistant placeholder is UI state, not a
 * model message; sending it would leave the request ending in an empty assistant turn.
 */
export function toChatHistory(turns: ChatTurn[]): ChatMessage[] {
  return turns
    .filter((turn) => !(turn.role === 'assistant' && turn.streaming))
    .map((turn) => ({ role: turn.role, content: turn.content }))
}

/**
 * Best-effort plugin id for an attached file, based on extension. Empty string means "unknown —
 * the user must pick a plugin in the UI before the file is sent with the chat request."
 */
export function guessPluginForFile(fileName: string): string {
  const lower = (fileName ?? '').toLowerCase()
  if (lower.endsWith('.xlsx') || lower.endsWith('.xls') || lower.endsWith('.xlsm')) {
    return 'fan.summer.excel'
  }
  if (lower.endsWith('.py')) {
    return 'fan.summer.offlinepython'
  }
  return ''
}

/** Files are input-only; a selected directory may also be the user's output target. */
export function grantAccessForAttachment(kind: PluginFileRef['kind']): 'read' | 'read-write' {
  return kind === 'directory' ? 'read-write' : 'read'
}
