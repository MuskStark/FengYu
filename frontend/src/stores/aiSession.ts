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
 * first opened, and each completed assistant turn is persisted (create on first save, update
 * thereafter). Streaming still targets the active conversation's assistant turn via a reactive()
 * proxy so token deltas repaint live.
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
    clearActiveFiles()
    return conv
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

  /** Select a conversation, lazy-loading its messages from the backend on first open. */
  async function select(id: number) {
    if (busy.value) return
    if (activeId.value !== id) clearActiveFiles()
    activeId.value = id
    error.value = null
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
          void persist(conv) // save the completed turn
        },
        onError: (message) => {
          const failedStreamId = currentStreamId
          error.value = message
          assistant.streaming = false
          busy.value = false
          handle = null
          currentStreamId = null
          if (failedStreamId) void api.cancelAiGeneration(failedStreamId).catch(() => {/* best effort */})
          void persist(conv)
        },
      })
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to start chat'
      assistant.streaming = false
      busy.value = false
    }
  }

  function stop() {
    handle?.close()
    handle = null
    const streamId = currentStreamId
    currentStreamId = null
    if (streamId) void api.cancelAiGeneration(streamId).catch(() => {/* best effort */})
    busy.value = false
    const t = active.value?.turns
    const last = t?.[t.length - 1]
    if (last && last.streaming) last.streaming = false
    if (active.value) void persist(active.value)
  }

  async function resolveConfirmation(item: ToolConfirmation, approve: boolean) {
    await actOnConfirmation(item, approve)
    const activity = active.value?.turns.flatMap(turn => turn.activities)
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
