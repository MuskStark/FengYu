import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import { openAiStream, type SseHandle } from '@/api/sse'
import type { AiPermissionMode, ChatArtifact, ChatMessage, ChatResource, ChatStartResponse, ConversationPayload, DraftAttachment, PersistedAttachment, PluginDescriptor } from '@/api/types'
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
  /** Display-only attachment record for a sent user message (persisted with the message). */
  attachments: PersistedAttachment[]
  /** Generated results attached to this assistant turn by the host save closure. */
  artifacts: ChatArtifact[]
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
  /** Composer draft — owned by THIS conversation, kept here across switches (4.3). */
  draft: string
  /**
   * Draft attachments — selections that live ONLY here until send (E01/E02): no backend call,
   * no grant, no copy happens at pick time. Browser File objects sit in the session-memory
   * browserFiles map, keyed by attachmentId.
   */
  draftAttachments: DraftAttachment[]
  /** Server-minted resource scope; created lazily on first send/output-target. */
  scopeId: string | null
  /** Aggregated input resources committed by past sends (never shared, 4.3). */
  resources: ChatResource[]
  /** Host-save output location (a real local path; desktop shells only). */
  outputTarget: string | null
  /**
   * Coding workspace root attached to the persisted conversation. While set, the backend binds
   * WorkspaceContext per turn and the model gets the read/write/edit/grep/glob tool family.
   */
  workspaceRoot: string | null
  /** In-flight scope/output/register operations belonging to this conversation (6.1). */
  attaching: number
  /** Artifact ids already surfaced on some turn — keeps turn attachment idempotent. */
  seenArtifactIds: Set<string>
  /**
   * True from the moment a save starts until a save SUCCEEDS. While set, unloadTurns keeps the
   * in-memory turns — they are the only copy of content whose last save failed or is still in
   * flight, and dropping them on a conversation switch would lose the user's messages.
   */
  unsaved: boolean
}

/**
 * Conversation-centric AI session store with backend persistence.
 *
 * History lives in the DB (via /api/ai/conversations) so it survives refresh/restart. The store
 * mirrors it in memory: summaries load on mount, a conversation's messages lazy-load when it is
 * first opened, and switching away releases its turns again. Each completed assistant turn is
 * persisted (create on first save, update thereafter). Streaming writes into its own
 * conversation's assistant turn via a reactive() proxy so token deltas repaint live — including
 * when the user switches to another conversation mid-stream.
 *
 * Selections are DRAFT-ONLY until send (E01/E02): attaching a file writes a DraftAttachment
 * into the conversation that opened the picker and nothing else — no network call, no grant,
 * no server copy. The send flow prepares a server-side send transaction (host copies of the
 * attachments), then commits it through the chat POST with a sendId, which makes the whole
 * send idempotent (one message, one execution per sendId — E05/E06). The scope registry owns
 * the committed resources; this store just mirrors them.
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
  /**
   * Browser File objects behind draft attachments (E01: uploads wait for send; §14: a File is
   * session memory and never pretends to survive serialization). Keyed by attachmentId.
   */
  const browserFiles = new Map<string, File[]>()
  // The conversation/turn an in-flight stream writes into. The user may switch to
  // another conversation mid-stream (the streaming closures bind these directly, so
  // tokens keep landing in the right turn regardless of what is on screen) — stop()
  // and the lazy unload below must therefore target the streaming conversation, not
  // whatever happens to be active when they run.
  let streamingConv: Conversation | null = null
  let streamingTurn: ChatTurn | null = null
  /**
   * Serialized save chains per conversation id (kept OUTSIDE the reactive state). A newer
   * snapshot must never race an older save's create/update request; each save runs after the
   * previous one settles and snapshots the turns at its own execution time.
   */
  const saveChains = new Map<number, Promise<void>>()

  /**
   * Cached installed-plugin descriptors (details view names the tools that may consume a
   * resource). Loaded lazily when the user opens the attach affordance.
   */
  const installedPlugins = ref<PluginDescriptor[]>([])
  async function loadInstalledPlugins() {
    installedPlugins.value = await api.getPlugins()
  }

  const active = computed<Conversation | null>(
    () => conversations.value.find((c) => c.id === activeId.value) ?? null,
  )
  const turns = computed<ChatTurn[]>(() => active.value?.turns ?? [])
  /** Derived view of the ACTIVE conversation's resources (the only list the UI renders). */
  const activeResources = computed<ChatResource[]>(() => active.value?.resources ?? [])
  /** Derived view of the ACTIVE conversation's unsent draft attachments. */
  const activeDraftAttachments = computed<DraftAttachment[]>(() => active.value?.draftAttachments ?? [])
  const activeOutputTarget = computed<string | null>(() => active.value?.outputTarget ?? null)

  function newConversation(): Conversation {
    const conv: Conversation = {
      id: ++convSeq,
      backendId: null,
      title: '',
      turns: [],
      createdAt: Date.now(),
      loaded: true, // brand-new, nothing to fetch
      draft: '',
      draftAttachments: [],
      scopeId: null,
      resources: [],
      outputTarget: null,
      workspaceRoot: null,
      attaching: 0,
      seenArtifactIds: new Set<string>(),
      unsaved: false,
    }
    conversations.value.unshift(conv)
    activeId.value = conv.id
    error.value = null
    return conv
  }

  /**
   * A conversation may be reused as the "New chat" blank ONLY when it is provably empty:
   * loaded (an unloaded history row with turns.length === 0 is NOT blank — A05), no turns,
   * no draft text, no draft attachments, no resources, no output target, no in-flight
   * operation, and not the one a stream is still writing into (A04/A06).
   */
  function isBlankReusable(conv: Conversation): boolean {
    return conv.loaded
      && conv.turns.length === 0
      && !conv.draft.trim()
      && conv.draftAttachments.length === 0
      && conv.resources.length === 0
      && conv.outputTarget === null
      && conv.workspaceRoot === null
      && conv.attaching === 0
      && conv !== streamingConv
  }

  /**
   * Sidebar "New chat": reuse an existing provably-blank conversation instead of minting a new
   * one on every click. A conversation carrying a draft or attachments stays where it is —
   * switching away must never lose user work (4.3).
   */
  function newChat(): Conversation {
    const blank = conversations.value.find(isBlankReusable)
    if (blank) {
      activeId.value = blank.id
      error.value = null
      return blank
    }
    return newConversation()
  }

  function ensureActive(): Conversation {
    return active.value ?? newConversation()
  }

  /**
   * The conversation an attach/output gesture works on. Attaching IS a "start this
   * conversation" gesture: with no conversation yet (fresh app state), one is created so the
   * picker actually opens — the old early-return here silently swallowed every menu click.
   */
  function ensureConversation(): Conversation {
    return active.value ?? newConversation()
  }

  /** Load the sidebar summaries (no messages yet). Called once on shell mount. */
  async function loadHistory() {
    if (historyLoaded.value) return
    try {
      const list = await api.listConversations()
      // MERGE, never replace: a conversation minted while this request was in flight (the
      // user clicked 新对话 during app start) holds live state — its turns, draft, and the
      // activeId pointing at it. Replacing the array would orphan it (blank pane, dead
      // composer, streams writing into an invisible conversation). Rows are keyed by
      // backendId; local conversations keep their positions on top.
      const present = new Set(conversations.value.map((c) => c.backendId))
      const fresh = list
        .filter((s) => !present.has(s.id))
        .map((s) => ({
          id: ++convSeq,
          backendId: s.id,
          title: s.title,
          turns: [] as ChatTurn[],
          createdAt: Date.parse(s.createdAt) || Date.now(),
          loaded: false,
          draft: '',
          draftAttachments: [],
          scopeId: null,
          resources: [] as ChatResource[],
          outputTarget: null,
          workspaceRoot: s.workspaceRoot ?? null,
          attaching: 0,
          seenArtifactIds: new Set<string>(),
          unsaved: false,
        }))
      conversations.value = [...conversations.value, ...fresh]
      historyLoaded.value = true
    } catch {
      // Backend unreachable — keep whatever is in memory; StatusBar surfaces connectivity.
    }
  }

  /**
   * Select a conversation, lazy-loading its messages from the backend on first open. Switching
   * while a stream runs is allowed: the stream's callbacks close over their own
   * conversation/turn, so generation continues into the backgrounded conversation and the
   * switch only changes what is on screen. Resource ownership does not change on switch.
   */
  async function select(id: number) {
    const previous = active.value
    activeId.value = id
    error.value = null
    if (previous && previous.id !== id) unloadTurns(previous)
    const conv = conversations.value.find((c) => c.id === id)
    if (!conv || conv.loaded || conv.backendId == null) return
    await loadConversationTurns(conv)
  }

  /**
   * Fetches a persisted conversation's messages into memory (the lazy-load behind select and
   * the pre-send guard in {@link send}). Returns false when the fetch fails: the conversation
   * STAYS unloaded and the error is surfaced — an unloaded row must never look like a blank
   * chat, because sending into one would later save a partial turn list over the server's
   * history.
   */
  async function loadConversationTurns(conv: Conversation): Promise<boolean> {
    try {
      const detail = await api.getConversation(conv.backendId!)
      conv.turns = detail.messages.map((m) => ({
        id: ++seq,
        role: m.role,
        content: m.content,
        thinking: m.thinking,
        streaming: false,
        confirmations: [], activities: [],
        // E13: persisted attachment metadata displays again after a restart; it never carries
        // authorization (the scope registry is gone, so old attachments show as unavailable
        // until the user re-adds the file).
        attachments: m.attachments ?? [],
        artifacts: [],
      }))
      conv.title = detail.title
      conv.workspaceRoot = detail.workspaceRoot ?? null
      conv.loaded = true
      // C09: surface results whose save never completed before the app restarted. Only
      // content and state recover — the original directory authorization does not.
      try {
        const pending = await api.listPendingChatArtifacts(conv.backendId!)
        const unseen = pending.filter(a => !conv.seenArtifactIds.has(a.artifactId))
        if (unseen.length) {
          const last = [...conv.turns].reverse().find(t => t.role === 'assistant')
          if (last) {
            last.artifacts.push(...unseen)
            unseen.forEach(a => conv.seenArtifactIds.add(a.artifactId))
          }
        }
      } catch { /* artifact recovery is best-effort */ }
      return true
    } catch {
      // Leave the row unloaded (a retry on the next select tries again) but say so — a silent
      // blank pane reads as "the conversation is empty" and invites typing into it.
      error.value = 'Failed to load this conversation — select it again to retry'
      return false
    }
  }

  /**
   * Release a switched-away conversation's turns so memory does not grow without
   * bound in the long-lived desktop shell. The summary row stays in the sidebar;
   * reopen re-fetches the messages from the backend. Never-persisted conversations
   * (backendId null) hold the only copy of their turns, the conversation an
   * in-flight stream is still writing into must keep its reactive turn, and a
   * conversation whose last save failed (or is still running) keeps its turns —
   * they are the only copy of unsaved content. Resources, draft, and output
   * target stay — they belong to the conversation, not the view.
   */
  function unloadTurns(conv: Conversation) {
    if (conv.backendId == null) return
    if (busy.value && conv === streamingConv) return
    if (conv.unsaved) return
    conv.turns = []
    conv.loaded = false
  }

  async function removeConversation(id: number) {
    const conv = conversations.value.find((c) => c.id === id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    saveChains.delete(id)
    if (activeId.value === id) {
      // The fallback must go through select(): pointing activeId at a bare history row would
      // render a BLANK pane for a conversation that actually has messages (and a send from
      // that state would later save a partial list over its history). With nothing left, land
      // on a fresh blank chat instead of a dead activeId.
      const fallback = conversations.value[0]
      if (fallback) void select(fallback.id)
      else newConversation()
    }
    // Draft browser files die with their conversation (never serialized, never uploaded).
    if (conv) for (const a of conv.draftAttachments) browserFiles.delete(a.attachmentId)
    if (conv?.backendId != null) {
      try {
        await api.deleteConversation(conv.backendId)
      } catch {
        /* best effort — the row stays but the UI already dropped it */
      }
    }
    // Deleting a conversation also closes its resource scope: grants are revoked and
    // still-unsaved artifacts are reclaimed (7.3). Saved results stay on the user's disk.
    if (conv?.scopeId) {
      const scopeId = conv.scopeId
      void api.closeChatScope(scopeId).catch(() => {/* best effort; server sweeps idle scopes */})
    }
  }

  function toPayload(conv: Conversation): ConversationPayload {
    return {
      title: conv.title,
      messages: conv.turns.map((t) => ({
        role: t.role,
        content: t.content,
        thinking: t.thinking,
        // §14-1: sent attachments persist with their message — display metadata only.
        ...(t.role === 'user' && t.attachments.length ? { attachments: t.attachments } : {}),
      })),
    }
  }

  /**
   * Persist a conversation: create on first save, update afterward. Saves are serialized per
   * conversation (see {@link saveChains}) and each save flags the conversation `unsaved` until
   * it SUCCEEDS — a failed save keeps the in-memory copy (unload is blocked while unsaved), is
   * surfaced through {@link error}, and is retried by the next completed turn.
   */
  function persist(conv: Conversation) {
    const live = liveConv(conv)
    if (!live) return
    const chained = (saveChains.get(live.id) ?? Promise.resolve())
      .catch(() => {}) // the previous save's outcome was already handled; never block the next
      .then(() => doPersist(live))
    saveChains.set(live.id, chained)
  }

  async function doPersist(conv: Conversation) {
    if (!stillExists(conv)) return
    // A never-persisted conversation with no turns has nothing to save. Creating a row here
    // (e.g. stop clicked while a send was still preparing) minted phantom "Untitled" history
    // entries that loadHistory then resurrected on every restart.
    if (conv.backendId == null && conv.turns.length === 0) return
    // A persisted-but-unloaded conversation holds NO turns in memory — PUTting that empty
    // list would REPLACE the server-side history with nothing. It must be (re)loaded before
    // any save; every legitimate save path operates on a loaded conversation.
    if (conv.backendId != null && !conv.loaded) return
    conv.unsaved = true
    try {
      if (conv.backendId == null) {
        const saved = await api.createConversation(toPayload(conv))
        conv.backendId = saved.id
      } else {
        await api.updateConversation(conv.backendId, toPayload(conv))
      }
      conv.unsaved = false
      // The first save mints the backend id — bind the resource scope now so artifacts
      // can recover by conversation after a restart.
      if (conv.scopeId && conv.backendId != null) {
        const scopeId = conv.scopeId
        const backendId = conv.backendId
        void api.bindChatScopeConversation(scopeId, backendId).catch(() => {/* best effort */})
      }
    } catch {
      // NOT silently swallowed: the turns stay in memory (unload is blocked while unsaved),
      // a later turn retries the save, and the user is told it failed.
      error.value = 'Conversation save failed — the messages are kept in memory and the save retries with the next message'
    }
  }

  // ── draft attachments (selection never calls the server — E01/E02) ─────────────────

  /**
   * Resolve the conversation's LIVE reactive proxy by id. Callers capture the conversation at
   * gesture time (possibly the raw object); mutating the raw target would bypass Vue's
   * reactivity entirely, so every state change goes through this proxy. Null once deleted.
   */
  function liveConv(conv: Conversation): Conversation | null {
    return conversations.value.find(c => c.id === conv.id) ?? null
  }

  function stillExists(conv: Conversation): boolean {
    return liveConv(conv) !== null
  }

  function newAttachmentId(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `att-${Date.now()}-${Math.random().toString(36).slice(2)}`
  }

  /**
   * Attaches a desktop-native selection as a DRAFT: the path is display-only here and becomes a
   * server-side host copy only when the send transaction prepares (E01: zero grants, zero
   * copies, zero workers between pick and send). Cancel/removal is purely local (E02).
   */
  function attachNative(conv: Conversation, path: string, kind: 'file' | 'directory') {
    const live = liveConv(conv)
    if (!live) return
    live.draftAttachments.push({
      attachmentId: newAttachmentId(),
      kind,
      name: path.split(/[\\/]/).pop() ?? path,
      source: 'desktop-native',
      displayPath: path,
      status: 'selected',
    })
  }

  /** Attaches a browser file selection as a draft; the File object waits in memory for send. */
  function attachUpload(conv: Conversation, file: File) {
    const live = liveConv(conv)
    if (!live) return
    const attachmentId = newAttachmentId()
    browserFiles.set(attachmentId, [file])
    live.draftAttachments.push({
      attachmentId,
      kind: 'file',
      name: file.name,
      source: 'browser-file',
      status: 'selected',
    })
  }

  /** Attaches a browser directory selection (webkitRelativePath tree) as a draft. */
  function attachUploadDirectory(conv: Conversation, files: File[]) {
    const live = liveConv(conv)
    if (!live || files.length === 0) return
    const attachmentId = newAttachmentId()
    browserFiles.set(attachmentId, files)
    live.draftAttachments.push({
      attachmentId,
      kind: 'directory',
      name: files[0].webkitRelativePath.split('/')[0] || files[0].name,
      source: 'browser-file',
      status: 'selected',
    })
  }

  /** Removes a draft attachment — local only; nothing was ever registered server-side. */
  function removeDraftAttachment(conv: Conversation, attachmentId: string) {
    const live = liveConv(conv)
    if (!live) return
    live.draftAttachments = live.draftAttachments.filter(a => a.attachmentId !== attachmentId)
    browserFiles.delete(attachmentId)
  }

  /**
   * The scope backing a conversation, created lazily at send time (or when an output target is
   * set). The callback captures the conversation: if it was deleted while the request was in
   * flight, the fresh scope is closed again immediately — a late response must never attach to
   * something that no longer exists (6.1).
   */
  async function ensureScope(conv: Conversation): Promise<string> {
    if (conv.scopeId) return conv.scopeId
    conv.attaching++
    try {
      const scopeId = await api.createChatScope()
      if (!stillExists(conv)) {
        void api.closeChatScope(scopeId).catch(() => {/* scope never had resources */})
        throw new Error('The conversation was deleted while registering its resources')
      }
      conv.scopeId = scopeId
      return scopeId
    } finally {
      conv.attaching--
    }
  }

  /** Register a send result into the conversation that sent it (A07/A08). */
  function adoptResource(conv: Conversation, resource: ChatResource) {
    if (!stillExists(conv)) return false
    const existing = conv.resources.findIndex(r => r.resourceId === resource.resourceId)
    if (existing >= 0) conv.resources[existing] = resource
    else conv.resources.push(resource)
    return true
  }

  /** Sets (or clears) the host-save output location for the conversation that asked. */
  async function setOutputTarget(conv: Conversation, path: string | null) {
    const live = liveConv(conv)
    if (!live) return
    const scopeId = await ensureScope(live)
    if (!stillExists(live)) {
      if (path) void api.closeChatScope(scopeId).catch(() => {/* never adopted */})
      return
    }
    live.outputTarget = await api.setChatOutputTarget(scopeId, path)
  }

  /**
   * Attaches (or detaches) the coding workspace root. Unlike a draft attachment this is a
   * deliberate persist-gesture: the conversation row is created on demand so the root has
   * somewhere to live, mirroring the scope creation on first send.
   */
  async function setWorkspace(conv: Conversation, path: string | null) {
    const live = liveConv(conv)
    if (!live) return
    live.attaching++
    try {
      if (path == null) {
        if (live.backendId != null) await api.clearConversationWorkspace(live.backendId)
        live.workspaceRoot = null
        return
      }
      if (live.backendId == null) {
        const saved = await api.createConversation({ title: '', messages: [] })
        if (!stillExists(live)) return // deleted mid-flight; the orphan row is harmless
        live.backendId = saved.id
      }
      const { workspaceRoot } = await api.setConversationWorkspace(live.backendId, path)
      if (stillExists(live)) live.workspaceRoot = workspaceRoot
    } finally {
      live.attaching--
    }
  }

  /**
   * Removes one aggregated resource from its conversation (idempotent): one chip removal
   * retires every underlying grant, while a running turn drains safely server-side (A01/B02).
   */
  function removeResource(conv: Conversation, resourceId: string) {
    const live = liveConv(conv)
    if (!live) return
    live.resources = live.resources.filter(r => r.resourceId !== resourceId)
    if (live.scopeId) {
      void api.removeChatResource(live.scopeId, resourceId).catch(() => {/* best effort */})
    }
  }

  /** Re-snapshots a native read-only file; failure keeps the previous revision usable (5.2). */
  async function refreshResource(conv: Conversation, resourceId: string) {
    const live = liveConv(conv)
    if (!live?.scopeId) return
    const resource = await api.refreshChatResource(live.scopeId, resourceId)
    adoptResource(live, resource)
  }

  /**
   * Pulls the scope's artifacts and attaches any never-seen ones to the given assistant turn
   * (the completing turn). Called from the done callback — the backend settles registration
   * before emitting done, so this read cannot race the file work.
   */
  async function syncArtifacts(conv: Conversation, turn: ChatTurn | null) {
    const live = liveConv(conv)
    if (!live?.scopeId) return
    try {
      const artifacts = await api.listChatArtifacts(live.scopeId)
      if (!stillExists(live)) return
      for (const artifact of artifacts) {
        if (live.seenArtifactIds.has(artifact.artifactId)) continue
        live.seenArtifactIds.add(artifact.artifactId)
        const target = turn ?? [...live.turns].reverse().find(t => t.role === 'assistant') ?? null
        if (target) {
          const existing = target.artifacts.find(a => a.artifactId === artifact.artifactId)
          if (existing) Object.assign(existing, artifact)
          else target.artifacts.push(artifact)
        }
      }
    } catch {
      // artifact listing is best-effort; a retry happens on the next done
    }
  }

  /** Saves one artifact (retry included) and updates the owning turn's record in place. */
  async function saveArtifact(conv: Conversation, turn: ChatTurn, artifactId: string, targetPath: string) {
    const live = liveConv(conv)
    if (!live?.scopeId) return
    const updated = await api.saveChatArtifact(live.scopeId, artifactId, targetPath)
    const record = turn.artifacts.find(a => a.artifactId === artifactId)
    if (record) Object.assign(record, updated)
  }

  // ── chat ────────────────────────────────────────────────────────────────────────────

  /**
   * Monotonic send generation. Every send() claims the current value and re-checks it after
   * each await; stop() bumps it. Without this, a stop clicked while the send is still between
   * its awaits (scope creation, attachment preparation, the chat POST) was only half-applied:
   * the continuation went on to push its turns and open the stream anyway — with busy already
   * false, the composer showed the SEND button while an unstoppable stream wrote into the
   * conversation (and a second send could stack a second stream the backend rejects).
   */
  let sendEpoch = 0

  /** Removes a send's optimistic turns by IDENTITY (a historical bubble with the same text is never collateral) and restores the prompt into the draft — unless the user typed something newer. */
  function rollbackOptimisticTurns(conv: Conversation, userTurn: ChatTurn, assistant: ChatTurn, prompt: string) {
    const assistantIndex = conv.turns.indexOf(assistant)
    if (assistantIndex >= 0) conv.turns.splice(assistantIndex, 1)
    const userIndex = conv.turns.indexOf(userTurn)
    if (userIndex >= 0) conv.turns.splice(userIndex, 1)
    restoreDraft(conv, prompt)
    assistant.streaming = false
  }

  async function send(text: string) {
    const prompt = text.trim()
    if (!prompt || busy.value) return
    // §15.2-0: claim the send slot BEFORE the first await. The old code only set busy after the
    // scope/attachment preparation, so a double submit (Enter double-fire, double click) during
    // preparation minted a SECOND sendId — two aiChat POSTs and two streams the backend's
    // idempotency cannot merge. Every early exit below must release the flag again.
    busy.value = true
    error.value = null
    const epoch = ++sendEpoch
    // Own the streaming slot from the START, before the first await: while the send is still
    // preparing (history preload, scope creation, attachment upload — seconds with large
    // attachments), a conversation switch must not unload this conversation's turns, and
    // stop() must target it rather than whatever row happens to be active. Every exit below
    // releases the slot again through abandon().
    const conv = ensureActive()
    streamingConv = conv
    streamingTurn = null
    // Release the send slot ONLY while this send still owns it. A stop() — or a newer send —
    // bumped sendEpoch and already resettled busy/streaming state for its own continuation;
    // a stale exit clearing them unconditionally would kill the newer send's busy lock (two
    // stacked streams, the first one unstoppable) or retarget the streaming slot.
    const abandon = () => {
      if (epoch !== sendEpoch) return
      busy.value = false
      streamingConv = null
      streamingTurn = null
    }

    // Never send into a persisted conversation whose messages are not in memory (unloaded row
    // or a failed load): the save path PUTs the WHOLE turn list, so a partial local copy would
    // replace the server-side history — and the model would lose the prior context. Load first;
    // a failed load aborts the send with the draft intact.
    if (conv.backendId != null && !conv.loaded) {
      const loaded = await loadConversationTurns(conv)
      if (epoch !== sendEpoch) {
        // Stopped while the history was loading — nothing was sent.
        restoreDraft(conv, prompt)
        abandon()
        return
      }
      if (!loaded) {
        restoreDraft(conv, prompt)
        abandon()
        return
      }
    }
    if (!conv.title) conv.title = prompt.slice(0, 48)

    // §15.2-1/2: capture the payload ONCE. The one-time sendId makes the whole send
    // idempotent — a duplicate prepare or chat POST replays, it never duplicates (E05-E08).
    const sendId = newAttachmentId()
    const capturedAttachments = [...conv.draftAttachments]
    const capturedResourceIds = conv.resources
      .filter(r => r.status === 'ready' && r.purpose === 'input')
      .map(r => r.resourceId)

    let scopeId: string | null = null
    try {
      scopeId = await ensureScope(conv)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to start chat'
      restoreDraft(conv, prompt)
      abandon()
      return
    }
    if (epoch !== sendEpoch) {
      // Stopped while the scope was being created — nothing was sent; the idle scope is swept
      // server-side.
      restoreDraft(conv, prompt)
      abandon()
      return
    }

    // §15.2-3/4: prepare the send — native paths are copied server-side NOW (send-time
    // content truth), browser uploads stream into the transaction. A failure keeps the draft
    // untouched (E03/E04): nothing was consumed, the user removes what failed and retries.
    const natives = capturedAttachments.filter(a => a.source === 'desktop-native' && a.displayPath)
    const browser = capturedAttachments.filter(a => a.source === 'browser-file')
    try {
      await api.prepareChatSend(scopeId, sendId,
        natives.map(a => ({ attachmentId: a.attachmentId, path: a.displayPath!, kind: a.kind })))
      for (const attachment of browser) {
        const files = browserFiles.get(attachment.attachmentId) ?? []
        if (files.length === 0) {
          // A page refresh dropped the File objects (§16.2): name alone must never match.
          throw new Error(`"${attachment.name}" needs to be re-selected before sending`)
        }
        if (attachment.kind === 'directory') {
          await api.uploadChatSendDirectory(scopeId, sendId, attachment.attachmentId, files)
        } else {
          await api.uploadChatSendFile(scopeId, sendId, attachment.attachmentId, files[0])
        }
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to prepare the attachments'
      void api.abortChatSend(scopeId, sendId).catch(() => {/* server TTL also reclaims */})
      restoreDraft(conv, prompt)
      abandon()
      return
    }
    if (epoch !== sendEpoch) {
      // Stopped during preparation — roll the transaction back; nothing reached the model.
      void api.abortChatSend(scopeId, sendId).catch(() => {/* server TTL also reclaims */})
      restoreDraft(conv, prompt)
      abandon()
      return
    }

    const userTurn: ChatTurn = {
      id: ++seq, role: 'user', content: prompt, thinking: '', streaming: false,
      confirmations: [], activities: [],
      attachments: capturedAttachments.map(a => ({ name: a.name, kind: a.kind })),
      artifacts: [],
    }
    conv.turns.push(userTurn)
    // reactive() so streaming closures mutate the proxy (live repaint), not the raw object.
    const assistant = reactive<ChatTurn>({
      id: ++seq,
      role: 'assistant',
      content: '',
      thinking: '',
      streaming: true,
      confirmations: [],
      activities: [],
      attachments: [],
      artifacts: [],
    })
    conv.turns.push(assistant)
    busy.value = true
    streamingConv = conv
    streamingTurn = assistant

    let response: ChatStartResponse
    try {
      response = await api.aiChat(
        toChatHistory(conv.turns), [], permissionMode.value, null, null,
        { scopeId, resourceIds: capturedResourceIds, conversationId: conv.backendId, sendId },
      )
    } catch (e) {
      // §15.3: a timeout is not proof the send was rejected. Ask the server for the send's
      // fate first — a committed send replays its accepted result (E06), so the message and
      // its execution are recovered instead of duplicated or dropped.
      const recovered = await recoverCommittedSend(scopeId, sendId)
      if (!recovered) {
        error.value = e instanceof Error ? e.message : 'Failed to start chat'
        // The request never opened a stream — drop the optimistic turns by IDENTITY (a
        // historical user message with the same text must never be collateral damage), keep
        // the draft attachments (the server rolled the send back), and restore the prompt
        // unless the user typed something new while the request was in flight (E03/E08).
        rollbackOptimisticTurns(conv, userTurn, assistant, prompt)
        abandon()
        return
      }
      response = recovered
    }
    if (epoch !== sendEpoch) {
      // Stopped while the chat POST was in flight. The turn IS committed server-side — cancel
      // the generation it minted, never open the stream, and roll the optimistic turns back.
      void api.cancelAiGeneration(response.streamId).catch(() => {/* best effort */})
      rollbackOptimisticTurns(conv, userTurn, assistant, prompt)
      abandon()
      return
    }
    {
      currentStreamId = response.streamId
      // Typed-path grants adopted server-side come back as aggregated records; they belong to
      // THIS conversation even if the user has already switched away (A08).
      for (const resource of response.resources ?? []) adoptResource(conv, resource)
      // §15.2-7: consume ONLY the captured attachment ids — anything the user added or typed
      // while the send was in flight keeps waiting in the draft (E08).
      for (const attachment of capturedAttachments) {
        browserFiles.delete(attachment.attachmentId)
        const index = conv.draftAttachments.findIndex(d => d.attachmentId === attachment.attachmentId)
        if (index >= 0) conv.draftAttachments.splice(index, 1)
      }
      handle = openAiStream(response.streamId, {
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
          void syncArtifacts(conv, assistant) // surface generated results on this turn
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
    }
  }

  /** Restores the prompt into the draft after a failed send — never overwriting newer input. */
  function restoreDraft(conv: Conversation, prompt: string) {
    if (!conv.draft.trim()) conv.draft = prompt
  }

  /**
   * Asks the server for a send's fate after the chat POST failed (timeout / lost response).
   * Returns the committed chat result when the turn was actually accepted (E06), else null —
   * the caller keeps the draft and the user retries.
   */
  async function recoverCommittedSend(scopeId: string, sendId: string): Promise<ChatStartResponse | null> {
    try {
      const status = await api.getChatSendStatus(scopeId, sendId)
      return status.state === 'committed' && status.committedResult ? status.committedResult : null
    } catch {
      return null // status endpoint unreachable too — treat the send as not started
    }
  }

  function stop() {
    // Invalidate any send still between its awaits FIRST (see sendEpoch): its continuation
    // must not re-arm busy or open its stream after the user asked to stop. stop() during the
    // chat-POST window also leaves the committed streamId to the continuation, which cancels
    // it server-side the moment the response arrives.
    sendEpoch++
    handle?.close()
    handle = null
    const streamId = currentStreamId
    currentStreamId = null
    if (streamId) void api.cancelAiGeneration(streamId).catch(() => {/* best effort */})
    busy.value = false
    // The stream may target a conversation the user switched away from; settle its
    // placeholder turn and persist the conversation that owns it, not the active one.
    const conv = streamingConv ?? active.value
    const settled = streamingTurn
    if (settled) settled.streaming = false
    else {
      const t = active.value?.turns
      const last = t?.[t.length - 1]
      if (last && last.streaming) last.streaming = false
    }
    streamingConv = null
    streamingTurn = null
    // Snapshot-save only when the stream actually produced something. A stop during the
    // send's prepare/chat-POST window finds an EMPTY placeholder — the send's continuation
    // rolls the optimistic exchange back into the draft right after this, and snapshot-saving
    // it here would leave a duplicate user message plus an empty assistant turn server-side.
    if (conv && (!settled || settled.content || settled.thinking)) void persist(conv)
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
    ensureConversation,
    loadHistory,
    select,
    removeConversation,
    send,
    stop,
    resolveConfirmation,
    clear,
    activeResources,
    activeDraftAttachments,
    activeOutputTarget,
    attachNative,
    attachUpload,
    attachUploadDirectory,
    removeDraftAttachment,
    setOutputTarget,
    setWorkspace,
    removeResource,
    refreshResource,
    syncArtifacts,
    saveArtifact,
    installedPlugins,
    loadInstalledPlugins,
    permissionMode,
    isBlankReusable,
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
