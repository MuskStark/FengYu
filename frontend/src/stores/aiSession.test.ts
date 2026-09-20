import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { toChatHistory, type ChatTurn } from './aiSession'
import type { ChatResource, ChatSendStatus, ChatStartResponse, ConversationDetail, ConversationSummary, PluginDescriptor } from '@/api/types'
import { useAiSessionStore } from './aiSession'
import { api } from '@/api/client'
import { openAiStream } from '@/api/sse'

vi.mock('@/api/sse', () => ({
  openAiStream: vi.fn(),
}))

function turn(role: ChatTurn['role'], content: string, streaming = false): ChatTurn {
  return {
    id: 1,
    role,
    content,
    thinking: '',
    streaming,
    confirmations: [],
    activities: [],
    attachments: [],
    artifacts: [],
  }
}

function resource(overrides: Partial<ChatResource> = {}): ChatResource {
  return {
    resourceId: 'res_1',
    name: 'contacts.csv',
    kind: 'file',
    purpose: 'input',
    access: 'read',
    revision: 1,
    status: 'ready',
    source: 'native',
    size: 12,
    displayPath: '/tmp/contacts.csv',
    ...overrides,
  }
}

function sendStatus(overrides: Partial<ChatSendStatus> = {}): ChatSendStatus {
  return { sendId: 'send_1', state: 'prepared', attachments: [], ...overrides }
}

/** Spies for the quiet text-only send path (scope + empty prepare + chat). */
function mockQuietSendBasics() {
  vi.spyOn(api, 'createChatScope').mockResolvedValue('cs_1')
  vi.spyOn(api, 'prepareChatSend').mockResolvedValue(sendStatus())
  vi.spyOn(api, 'listChatArtifacts').mockResolvedValue([])
}

describe('AI session history', () => {
  it('does not send the streaming assistant placeholder to the model', () => {
    expect(toChatHistory([
      turn('user', 'previous'),
      turn('assistant', 'answer'),
      turn('user', 'current'),
      turn('assistant', '', true),
    ])).toEqual([
      { role: 'user', content: 'previous' },
      { role: 'assistant', content: 'answer' },
      { role: 'user', content: 'current' },
    ])
  })
})

describe('draft attachments (selection never calls the server)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('E01: picking a file or folder creates a draft only — no scope, no grant, no copy', () => {
    const store = useAiSessionStore()
    const conv = store.ensureConversation()
    const scope = vi.spyOn(api, 'createChatScope')
    const prepare = vi.spyOn(api, 'prepareChatSend')

    store.attachNative(conv, '/tmp/contacts.csv', 'file')
    store.attachNative(conv, '/tmp/资料', 'directory')

    expect(scope).not.toHaveBeenCalled()
    expect(prepare).not.toHaveBeenCalled()
    expect(store.activeDraftAttachments.map(a => a.name)).toEqual(['contacts.csv', '资料'])
    expect(store.activeDraftAttachments.map(a => a.status)).toEqual(['selected', 'selected'])
  })

  it('E02: removing a draft attachment is purely local', () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    const remove = vi.spyOn(api, 'removeChatResource')

    store.attachNative(conv, '/tmp/gone.csv', 'file')
    store.removeDraftAttachment(conv, store.activeDraftAttachments[0].attachmentId)

    expect(store.activeDraftAttachments.length).toBe(0)
    expect(remove).not.toHaveBeenCalled()
  })

  it('browser selections keep their File objects until send (never serialized)', () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    const file = new File(['a'], 'report.csv')

    store.attachUpload(conv, file)

    expect(store.activeDraftAttachments[0]).toMatchObject({
      name: 'report.csv', kind: 'file', source: 'browser-file', status: 'selected',
    })
  })

  it('A04: a draft with attachments is never reused as the new-chat blank', () => {
    const store = useAiSessionStore()
    const withAttachment = store.newConversation()
    store.attachNative(withAttachment, '/tmp/a.csv', 'file')
    const fresh = store.newChat()

    expect(fresh.id).not.toBe(withAttachment.id)
    expect(withAttachment.draftAttachments.length).toBe(1)
  })

  it('A04: a draft with text is preserved on new chat, not silently reset', () => {
    const store = useAiSessionStore()
    const withDraft = store.newConversation()
    withDraft.draft = 'half-written question'
    const fresh = store.newChat()

    expect(fresh.id).not.toBe(withDraft.id)
    expect(withDraft.draft).toBe('half-written question')
  })

  it('A05: an unloaded history conversation is never reused as blank', () => {
    const store = useAiSessionStore()
    const historyRow = store.newConversation()
    historyRow.loaded = false // messages never fetched — may carry turns server-side
    const fresh = store.newChat()

    expect(fresh.id).not.toBe(historyRow.id)
  })

  it('deleting a conversation drops its draft browser files along with it', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    vi.spyOn(api, 'closeChatScope').mockResolvedValue()
    vi.spyOn(api, 'deleteConversation').mockResolvedValue()
    store.attachUpload(conv, new File(['x'], 'x.csv'))

    await store.removeConversation(conv.id)

    expect(store.conversations.some(c => c.id === conv.id)).toBe(false)
  })
})

describe('send flow (prepare → commit, idempotent by sendId)', () => {
  beforeEach(() => {
    vi.restoreAllMocks() // api spies must not leak implementations between tests
    setActivePinia(createPinia())
  })

  it('send prepares the transaction, commits with the sendId, and consumes only the captured drafts', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    mockQuietSendBasics()
    const upload = vi.spyOn(api, 'uploadChatSendFile').mockResolvedValue(sendStatus())
    const chat = vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'st_1' })
    store.attachUpload(conv, new File(['a'], 'report.csv'))
    conv.resources.push(resource({ resourceId: 'res_kept' }))

    await store.send('summarize the report')

    // Prepare carried the draft (sendId + attachment); the upload joined the transaction.
    const sendId = vi.mocked(api.prepareChatSend).mock.calls[0][1]
    expect(sendId).toBeTruthy()
    expect(vi.mocked(api.prepareChatSend).mock.calls[0][2]).toEqual([])
    expect(upload).toHaveBeenCalledWith('cs_1', sendId, expect.any(String), expect.any(File))
    expect(chat).toHaveBeenCalledWith(
      expect.anything(), [], 'ask-for-approval', null, null,
      { scopeId: 'cs_1', resourceIds: ['res_kept'], conversationId: null, sendId },
    )
    // The captured draft was consumed; the user turn records the attachment metadata.
    expect(conv.draftAttachments.length).toBe(0)
    const userTurn = conv.turns.find(t => t.role === 'user')
    expect(userTurn?.attachments).toEqual([{ name: 'report.csv', kind: 'file' }])
  })

  it('E08: attachments added while the send is in flight stay in the draft', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    mockQuietSendBasics()
    let releaseChat: (value: ChatStartResponse) => void = () => {}
    let chatRequested = false
    vi.spyOn(api, 'aiChat').mockImplementation(() => {
      chatRequested = true
      return new Promise(resolve => { releaseChat = resolve })
    })
    store.attachNative(conv, '/tmp/captured.csv', 'file')

    const sending = store.send('go')
    await vi.waitFor(() => expect(chatRequested).toBe(true))
    store.attachNative(conv, '/tmp/typed-during-flight.csv', 'file')
    releaseChat({ streamId: 'st_2', resources: [] })
    await sending

    expect(conv.draftAttachments.map(a => a.name)).toEqual(['typed-during-flight.csv'])
  })

  it('E03/E04: a failed prepare keeps the draft and adds no turns', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    vi.spyOn(api, 'createChatScope').mockResolvedValue('cs_fail')
    vi.spyOn(api, 'prepareChatSend').mockRejectedValue(new Error('The selected file is no longer available: /tmp/x.csv'))
    const abort = vi.spyOn(api, 'abortChatSend').mockResolvedValue()
    const chat = vi.spyOn(api, 'aiChat')
    store.attachNative(conv, '/tmp/x.csv', 'file')

    await store.send('read it')

    expect(abort).toHaveBeenCalled()
    expect(chat).not.toHaveBeenCalled()
    expect(conv.draftAttachments.map(a => a.name)).toEqual(['x.csv'])
    expect(conv.turns.length).toBe(0)
    expect(store.error).toContain('no longer available')
  })

  it('§16.2: lost browser File objects (page refresh) fail the send loudly, draft kept', async () => {
    const store = useAiSessionStore()
    vi.spyOn(api, 'createChatScope').mockResolvedValue('cs_lost')
    vi.spyOn(api, 'prepareChatSend').mockResolvedValue(sendStatus())
    vi.spyOn(api, 'abortChatSend').mockResolvedValue()
    const chat = vi.spyOn(api, 'aiChat')
    // A draft restored after a reload carries metadata only — no File objects anywhere.
    const restored = store.newConversation()
    restored.draftAttachments.push({
      attachmentId: 'att_restored', kind: 'file', name: 'restored.csv',
      source: 'browser-file', status: 'selected',
    })

    await store.send('use it')

    expect(chat).not.toHaveBeenCalled()
    expect(store.error).toContain('re-selected')
    expect(restored.draftAttachments.length).toBe(1)
  })

  it('a failed chat POST removes the optimistic turns by identity and restores the draft text', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    mockQuietSendBasics()
    vi.spyOn(api, 'aiChat').mockRejectedValue(new Error('network down'))
    vi.spyOn(api, 'getChatSendStatus').mockResolvedValue(sendStatus({ state: 'failed' }))
    // A HISTORICAL user bubble with the very same text must survive the rollback.
    conv.turns.push({
      id: 99, role: 'user', content: 'read it', thinking: '', streaming: false,
      confirmations: [], activities: [], attachments: [], artifacts: [],
    })

    await store.send('read it')

    // Only the historical bubble remains; the optimistic turn was removed by identity.
    expect(conv.turns.filter(t => t.role === 'user').map(t => t.id)).toEqual([99])
    expect(conv.turns.some(t => t.role === 'assistant')).toBe(false)
    // The failed send restores the prompt into the draft.
    expect(conv.draft).toBe('read it')
    expect(store.error).toBe('network down')
  })

  it('E06: a lost chat response recovers via send status and opens the recovered stream', async () => {
    const sseCallbacks: Record<string, (payload: unknown) => void> = {}
    vi.mocked(openAiStream).mockImplementation(((_streamId: string, callbacks: Record<string, (payload: unknown) => void>) => {
      Object.assign(sseCallbacks, callbacks)
      return { close: () => {} } as unknown as ReturnType<typeof openAiStream>
    }) as typeof openAiStream)
    const store = useAiSessionStore()
    const conv = store.newConversation()
    mockQuietSendBasics()
    // The POST "times out" client-side, but the server actually accepted the send.
    vi.spyOn(api, 'aiChat').mockRejectedValue(new Error('timeout'))
    vi.spyOn(api, 'getChatSendStatus').mockResolvedValue(sendStatus({
      state: 'committed',
      committedResult: { streamId: 'st_recovered', resources: [] },
    }))

    await store.send('make the report')

    expect(store.error).toBeNull()
    // The accepted message stays — no duplicate and no drop.
    expect(conv.turns.filter(t => t.role === 'user').length).toBe(1)
    expect(vi.mocked(openAiStream).mock.calls.at(-1)?.[0]).toBe('st_recovered')
    expect(conv.draft).toBe('')
  })

  it('A06: switching to a new chat during generation keeps results in the origin', async () => {
    const store = useAiSessionStore()
    const origin = store.newConversation()
    mockQuietSendBasics()
    let releaseChat: (value: ChatStartResponse) => void = () => {}
    let chatRequested = false
    vi.spyOn(api, 'aiChat').mockImplementation(() => {
      chatRequested = true
      return new Promise(resolve => { releaseChat = resolve })
    })

    const sending = store.send('long task')
    await vi.waitFor(() => expect(chatRequested).toBe(true)) // the turn is in flight
    const blank = store.newChat() // user starts a fresh conversation mid-generation

    expect(blank.resources.length).toBe(0)
    expect(origin.turns.filter(t => t.role === 'user').length).toBe(1)

    releaseChat({ streamId: 'st_1', resources: [] })
    await sending
    // The generation still belongs to the origin conversation; the blank one stays empty.
    expect(origin.turns.at(-1)?.role).toBe('assistant')
    expect(origin.turns.at(-1)?.streaming).toBe(true)
    expect(blank.turns.length).toBe(0)
    expect(blank.resources.length).toBe(0)
    expect(blank.draftAttachments.length).toBe(0)
  })

  it('A08: typed-path resources from a delayed chat response stay in the origin conversation', async () => {
    const store = useAiSessionStore()
    const origin = store.newConversation()
    mockQuietSendBasics()
    let releaseChat: (value: ChatStartResponse) => void = () => {}
    let chatRequested = false
    vi.spyOn(api, 'aiChat').mockImplementation(() => {
      chatRequested = true
      return new Promise(resolve => { releaseChat = resolve })
    })

    const sending = store.send('read /tmp/notes.txt')
    await vi.waitFor(() => expect(chatRequested).toBe(true))
    const other = store.newChat()
    releaseChat({
      streamId: 'st_2',
      resources: [resource({ resourceId: 'res_typed', name: 'notes.txt' })],
    })
    await sending

    expect(origin.resources.map(r => r.resourceId)).toEqual(['res_typed'])
    expect(other.resources.length).toBe(0)
  })

  it('A01: response resources land as one record each and removal retires the whole record', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    mockQuietSendBasics()
    vi.spyOn(api, 'aiChat').mockResolvedValue({
      streamId: 'st_3', resources: [resource({ resourceId: 'res_agg' })],
    })
    await store.send('use the csv')

    expect(conv.resources.map(r => r.resourceId)).toEqual(['res_agg'])

    const revoke = vi.spyOn(api, 'removeChatResource').mockResolvedValue()
    store.removeResource(conv, 'res_agg')
    expect(conv.resources.length).toBe(0)
    expect(revoke).toHaveBeenCalledWith('cs_1', 'res_agg')
  })

  it('deleting a conversation closes its scope (grants + unsaved artifacts reclaimed)', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    conv.scopeId = 'cs_close'
    const close = vi.spyOn(api, 'closeChatScope').mockResolvedValue()
    vi.spyOn(api, 'deleteConversation').mockResolvedValue()

    await store.removeConversation(conv.id)

    expect(close).toHaveBeenCalledWith('cs_close')
  })

  it('output targets are per conversation and never leak to a new chat', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    vi.spyOn(api, 'createChatScope').mockResolvedValue('cs_out')
    vi.spyOn(api, 'setChatOutputTarget').mockResolvedValue('/Users/me/Desktop/reports')

    await store.setOutputTarget(conv, '/Users/me/Desktop/reports')
    const fresh = store.newChat()

    expect(conv.outputTarget).toBe('/Users/me/Desktop/reports')
    expect(store.activeOutputTarget).toBeNull() // the fresh conversation owns no save target
    expect(fresh.outputTarget).toBeNull()
  })

  it('P2: a double submit during attachment preparation is ignored (busy claimed before the first await)', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    vi.spyOn(api, 'createChatScope').mockResolvedValue('cs_race')
    let releasePrepare: (v: ChatSendStatus) => void = () => {}
    let prepareRequested = false
    vi.spyOn(api, 'prepareChatSend').mockImplementation(() => {
      prepareRequested = true
      return new Promise(resolve => { releasePrepare = resolve })
    })
    const chat = vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'st_race' })

    const first = store.send('first question')
    await vi.waitFor(() => expect(prepareRequested).toBe(true))
    // Double submit (Enter double-fire / double click) while the first send is still preparing.
    await store.send('second question')

    expect(store.busy).toBe(true) // the slot is claimed for the whole first send
    releasePrepare(sendStatus())
    await first

    // Exactly ONE send transaction: one prepare, one aiChat carrying the first prompt.
    expect(vi.mocked(api.prepareChatSend)).toHaveBeenCalledTimes(1)
    expect(chat).toHaveBeenCalledTimes(1)
    expect((chat.mock.calls[0][0] as { content: string }[]).at(-1)?.content).toBe('first question')
    expect(conv.turns.filter(t => t.role === 'user').length).toBe(1)
  })

  it('P2: a failed prepare releases the busy flag so the next send works', async () => {
    const store = useAiSessionStore()
    store.newConversation()
    vi.spyOn(api, 'createChatScope').mockResolvedValue('cs_rel')
    vi.spyOn(api, 'prepareChatSend').mockRejectedValue(new Error('boom'))
    vi.spyOn(api, 'abortChatSend').mockResolvedValue()
    const chat = vi.spyOn(api, 'aiChat')

    await store.send('try once')
    expect(store.busy).toBe(false)
    expect(chat).not.toHaveBeenCalled()

    vi.spyOn(api, 'prepareChatSend').mockResolvedValue(sendStatus())
    chat.mockResolvedValue({ streamId: 'st_retry' })
    await store.send('try again')

    expect(chat).toHaveBeenCalledTimes(1)
    expect(store.busy).toBe(true) // the retry's stream is live
  })
})

describe('conversation save failures keep the in-memory copy', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
  })

  it('P2: a failed save blocks the unload on switch-away, surfaces an error, and retries on the next turn', async () => {
    const sseCallbacks: Record<string, (payload: unknown) => void> = {}
    vi.mocked(openAiStream).mockImplementation(((_streamId: string, callbacks: Record<string, (payload: unknown) => void>) => {
      Object.assign(sseCallbacks, callbacks)
      return { close: () => {} } as unknown as ReturnType<typeof openAiStream>
    }) as typeof openAiStream)
    const store = useAiSessionStore()
    const conv = store.newConversation()
    conv.backendId = 7 // an already-persisted conversation → saves go through updateConversation
    mockQuietSendBasics()
    vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'st_save1' })
    const update = vi.spyOn(api, 'updateConversation').mockRejectedValue(new Error('disk full'))

    await store.send('make the report')
    sseCallbacks.onDone({ text: 'done' })
    await vi.waitFor(() => expect(conv.unsaved).toBe(true))
    expect(store.error).toContain('save failed')

    // Switching away must NOT drop the turns — they are the only copy of the unsaved content.
    const other = store.newConversation()
    await store.select(other.id)
    expect(conv.turns.length).toBe(2)
    expect(conv.loaded).toBe(true)

    // The next completed turn retries the save; success clears the unsaved flag.
    update.mockResolvedValue(undefined as never) // the store ignores the update result
    await store.select(conv.id)
    await store.send('another question')
    sseCallbacks.onDone({ text: 'done again' })
    await vi.waitFor(() => expect(conv.unsaved).toBe(false))

    await store.select(other.id)
    expect(conv.turns.length).toBe(0) // unload is allowed again after the successful save
    expect(conv.loaded).toBe(false)
  })
})

describe('artifact surfacing', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('done attaches new artifacts to the completing assistant turn', async () => {
    const sseCallbacks: Record<string, (payload: unknown) => void> = {}
    vi.mocked(openAiStream).mockImplementation(((_streamId: string, callbacks: Record<string, (payload: unknown) => void>) => {
      Object.assign(sseCallbacks, callbacks)
      return { close: () => {} } as unknown as ReturnType<typeof openAiStream>
    }) as typeof openAiStream)

    const store = useAiSessionStore()
    const conv = store.newConversation()
    mockQuietSendBasics()
    vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'st_art' })
    vi.spyOn(api, 'listChatArtifacts').mockResolvedValue([
      { artifactId: 'art_1', name: '报表.xlsx', state: 'saved', size: 10, createdAt: 'now', savedPath: '/out/报表.xlsx' },
    ])

    await store.send('make the report')
    sseCallbacks.onDone({ text: 'done' })
    await vi.waitFor(() => {
      const assistantTurn = conv.turns.find(t => t.role === 'assistant')
      expect(assistantTurn?.artifacts.map(a => a.artifactId)).toEqual(['art_1'])
      expect(assistantTurn?.artifacts[0].savedPath).toBe('/out/报表.xlsx')
    })
  })
})

describe('AI session installed plugins (details view source)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loadInstalledPlugins caches the plugin descriptor list', async () => {
    const store = useAiSessionStore()
    expect(store.installedPlugins).toEqual([])
    const excel: PluginDescriptor = {
      id: 'fan.summer.excel', name: 'Excel', description: '', category: 'OTHER',
      icon: '', iconStyle: '', version: '1', uiEntry: '', supportsAi: true, source: 'OFFICIAL',
      enabled: true, permissions: ['files.read'],
    }
    vi.spyOn(api, 'getPlugins').mockResolvedValue([excel])
    await store.loadInstalledPlugins()
    expect(store.installedPlugins.map((p) => p.id)).toEqual(['fan.summer.excel'])
  })
})

function summaryRow(id: number, title: string): ConversationSummary {
  return { id, title, createdAt: '2026-09-01T00:00:00', updatedAt: '2026-09-01T00:00:00' }
}

function detailRow(id: number, title: string, messages: Array<[string, string]>): ConversationDetail {
  return {
    ...summaryRow(id, title),
    messages: messages.map(([role, content]) => ({ role: role as 'user' | 'assistant', content, thinking: '' })),
  }
}

/** Captures the stream callbacks so tests can drive tokens/done like the real SSE bridge. */
function captureStreamCallbacks() {
  const sseCallbacks: Record<string, (payload: unknown) => void> = {}
  vi.mocked(openAiStream).mockImplementation(((_streamId: string, callbacks: Record<string, (payload: unknown) => void>) => {
    Object.assign(sseCallbacks, callbacks)
    return { close: () => {} } as unknown as ReturnType<typeof openAiStream>
  }) as typeof openAiStream)
  return sseCallbacks
}

describe('switching and deletion never strand an unloaded conversation', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
  })

  it('deleting the active conversation selects and loads the fallback — no blank pane', async () => {
    const store = useAiSessionStore()
    vi.spyOn(api, 'listConversations').mockResolvedValue([summaryRow(1, 'first'), summaryRow(2, 'second')])
    vi.spyOn(api, 'deleteConversation').mockResolvedValue()
    vi.spyOn(api, 'listPendingChatArtifacts').mockResolvedValue([])
    const get = vi.spyOn(api, 'getConversation')
      .mockResolvedValueOnce(detailRow(1, 'first', []))
      .mockResolvedValue(detailRow(2, 'second', [['user', 'old question'], ['assistant', 'old answer']]))
    await store.loadHistory()
    await store.select(1)

    await store.removeConversation(1)

    expect(store.activeId).toBe(store.conversations[0].id)
    await vi.waitFor(() => {
      expect(get).toHaveBeenCalledWith(2)
      expect(store.turns.map(t => t.content)).toEqual(['old question', 'old answer'])
    })
  })

  it('deleting the last conversation lands on a fresh blank chat, not a dead activeId', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    vi.spyOn(api, 'deleteConversation').mockResolvedValue()

    await store.removeConversation(conv.id)

    expect(store.conversations).toHaveLength(1)
    expect(store.active?.backendId).toBeNull()
    expect(store.turns).toEqual([])
  })

  it('sending into an unloaded history conversation loads it first — the save keeps its history', async () => {
    const sseCallbacks = captureStreamCallbacks()
    const store = useAiSessionStore()
    // The stranded state: a persisted conversation becomes active without ever loading its
    // messages (the old removeConversation fallback / a failed select fetch).
    const conv = store.newConversation()
    conv.backendId = 42
    conv.loaded = false
    mockQuietSendBasics()
    vi.spyOn(api, 'getConversation').mockResolvedValue(detailRow(42, 'old chat', [
      ['user', 'm1'], ['assistant', 'm2'], ['user', 'm3'],
      ['assistant', 'm4'], ['user', 'm5'], ['assistant', 'm6'],
    ]))
    const chat = vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'st_guard' })
    const update = vi.spyOn(api, 'updateConversation').mockResolvedValue(detailRow(42, 'old chat', []))

    const sending = store.send('hello?')
    await vi.waitFor(() => expect(chat).toHaveBeenCalled())
    sseCallbacks.onDone?.({ text: 'answer' })
    await sending
    await vi.waitFor(() => expect(update).toHaveBeenCalled())

    // The whole exchange reached the model AND the save — history is never replaced by the
    // single new turn pair.
    expect(chat.mock.calls[0][0]).toHaveLength(7) // 6 prior + the new user message
    expect(update.mock.calls[0][1].messages).toHaveLength(8) // …plus the streamed answer
  })

  it('a failed history load aborts the send with the draft intact', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    conv.backendId = 42
    conv.loaded = false
    mockQuietSendBasics()
    vi.spyOn(api, 'getConversation').mockRejectedValue(new Error('boom'))
    const chat = vi.spyOn(api, 'aiChat')

    await store.send('hello?')

    expect(chat).not.toHaveBeenCalled()
    expect(store.busy).toBe(false)
    expect(store.error).toContain('Failed to load')
    expect(conv.turns).toHaveLength(0)
    expect(conv.draft).toBe('hello?')
  })
})

describe('loadHistory merges instead of replacing', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
  })

  it('a new chat created while the history fetch is in flight survives with its activeId', async () => {
    let resolveList: (v: ConversationSummary[]) => void = () => {}
    vi.spyOn(api, 'listConversations').mockReturnValue(new Promise(res => { resolveList = res }))
    const store = useAiSessionStore()
    const loading = store.loadHistory()

    const fresh = store.newChat() // the user clicks 新对话 while the fetch is in flight
    resolveList([summaryRow(100, 'history row')])
    await loading

    expect(store.conversations).toHaveLength(2)
    expect(store.conversations[0].backendId).toBeNull() // the new chat stayed on top
    expect(store.conversations[1].backendId).toBe(100)
    expect(store.activeId).toBe(fresh.id)
    expect(store.active).not.toBeNull() // the pane keeps its conversation instead of blanking
    expect(store.historyLoaded).toBe(true)
  })
})

describe('stopping a send that has not opened its stream yet', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
  })

  it('stop during preparation aborts the send and restores the draft', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    vi.spyOn(api, 'createChatScope').mockResolvedValue('cs_stop1')
    let releasePrepare: (v: ChatSendStatus) => void = () => {}
    let prepareRequested = false
    vi.spyOn(api, 'prepareChatSend').mockImplementation(() => {
      prepareRequested = true
      return new Promise(res => { releasePrepare = res })
    })
    const abort = vi.spyOn(api, 'abortChatSend').mockResolvedValue()
    const chat = vi.spyOn(api, 'aiChat')

    const sending = store.send('go')
    await vi.waitFor(() => expect(prepareRequested).toBe(true))
    store.stop()
    releasePrepare(sendStatus())
    await sending

    expect(chat).not.toHaveBeenCalled()
    expect(abort).toHaveBeenCalled()
    expect(store.busy).toBe(false)
    expect(conv.turns).toHaveLength(0)
    expect(conv.draft).toBe('go')
  })

  it('stop during the chat POST cancels the committed stream and never opens it', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    mockQuietSendBasics()
    let releaseChat: (v: ChatStartResponse) => void = () => {}
    let chatRequested = false
    vi.spyOn(api, 'aiChat').mockImplementation(() => {
      chatRequested = true
      return new Promise(res => { releaseChat = res })
    })
    const cancel = vi.spyOn(api, 'cancelAiGeneration').mockResolvedValue()
    vi.spyOn(api, 'createConversation').mockResolvedValue(detailRow(77, '', [])) // stop()'s snapshot save

    const sending = store.send('make the report')
    await vi.waitFor(() => expect(chatRequested).toBe(true))
    store.stop()
    releaseChat({ streamId: 'st_stopped' })
    await sending

    // No stream was opened for the stopped send (module-mock call history accumulates across
    // tests, so scope the assertion to this streamId).
    expect(vi.mocked(openAiStream).mock.calls.filter(c => c[0] === 'st_stopped')).toHaveLength(0)
    expect(cancel).toHaveBeenCalledWith('st_stopped') // the committed generation IS ended
    expect(store.busy).toBe(false)
    expect(conv.turns).toHaveLength(0) // optimistic turns rolled back
    expect(conv.draft).toBe('make the report')
  })
})

describe('rc.3 review regressions: prepare-window switching, stale exits, unloaded saves', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
  })

  it('P1: switching away during preparation keeps the history — the save never replaces it', async () => {
    const sseCallbacks = captureStreamCallbacks()
    const store = useAiSessionStore()
    const other = store.newConversation()
    const conv = store.newConversation()
    conv.backendId = 7
    conv.loaded = true
    conv.turns = [turn('user', 'm1'), turn('assistant', 'm2')]
    mockQuietSendBasics()
    let releasePrepare: (v: ChatSendStatus) => void = () => {}
    let prepareRequested = false
    vi.spyOn(api, 'prepareChatSend').mockImplementation(() => {
      prepareRequested = true
      return new Promise(res => { releasePrepare = res })
    })
    const chat = vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'st_switch' })
    const update = vi.spyOn(api, 'updateConversation').mockResolvedValue(detailRow(7, 't', []))

    const sending = store.send('new question')
    await vi.waitFor(() => expect(prepareRequested).toBe(true))
    // The user switches conversations while attachments/scope are still preparing.
    await store.select(other.id)
    expect(conv.loaded).toBe(true) // the sending conversation was NOT unloaded
    expect(conv.turns).toHaveLength(2)

    releasePrepare(sendStatus())
    await vi.waitFor(() => expect(chat).toHaveBeenCalled())
    sseCallbacks.onDone?.({ text: 'answer' })
    await sending
    await vi.waitFor(() => expect(update).toHaveBeenCalled())

    // Full history to the model AND to the save — never just the single new exchange.
    expect(chat.mock.calls[0][0]).toHaveLength(3)
    expect(update.mock.calls[0][1].messages).toHaveLength(4)
  })

  it('P1: stop() never PUTs an empty message list over an unloaded conversation', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    conv.backendId = 42
    conv.loaded = false // a persisted history row whose messages are not in memory
    const update = vi.spyOn(api, 'updateConversation').mockResolvedValue(detailRow(42, 't', []))

    store.stop()

    expect(update).not.toHaveBeenCalled() // PUTting [] would erase the server-side history
  })

  it('P2: a stale send exit cannot kill a newer send\'s busy slot (no stacked streams)', async () => {
    const sseCallbacks = captureStreamCallbacks()
    const store = useAiSessionStore()
    store.newConversation()
    vi.spyOn(api, 'createChatScope').mockResolvedValue('cs_stale')
    let releaseFirst: (v: ChatSendStatus) => void = () => {}
    let calls = 0
    vi.spyOn(api, 'prepareChatSend').mockImplementation(() => {
      calls += 1
      if (calls === 1) return new Promise(res => { releaseFirst = res })
      return Promise.resolve(sendStatus())
    })
    vi.spyOn(api, 'abortChatSend').mockResolvedValue()
    const chat = vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'st_second' })

    const first = store.send('first question')
    await vi.waitFor(() => expect(calls).toBe(1))
    store.stop() // invalidates the first send…
    const second = store.send('second question') // …and the user immediately resends
    await vi.waitFor(() => expect(chat).toHaveBeenCalled())
    releaseFirst(sendStatus()) // the FIRST send's continuation finally resumes — it is stale
    await first
    await second

    // The stale exit must not release the second send's busy claim: exactly one POST, one
    // stream, and the slot is still held by the live stream.
    expect(store.busy).toBe(true)
    expect(chat).toHaveBeenCalledTimes(1)
    expect((chat.mock.calls[0][0] as { content: string }[]).at(-1)?.content).toBe('second question')
    expect(vi.mocked(openAiStream).mock.calls.filter(c => c[0] === 'st_second')).toHaveLength(1)
    sseCallbacks.onDone?.({ text: 'done' })
    expect(store.busy).toBe(false)
  })

  it('P2: stop during the chat-POST window does not snapshot-save the empty exchange', async () => {
    const store = useAiSessionStore()
    const conv = store.newConversation()
    conv.backendId = 7
    conv.loaded = true
    conv.turns = [turn('user', 'm1'), turn('assistant', 'm2')]
    mockQuietSendBasics()
    let releaseChat: (v: ChatStartResponse) => void = () => {}
    let chatRequested = false
    vi.spyOn(api, 'aiChat').mockImplementation(() => {
      chatRequested = true
      return new Promise(res => { releaseChat = res })
    })
    const cancel = vi.spyOn(api, 'cancelAiGeneration').mockResolvedValue()
    const update = vi.spyOn(api, 'updateConversation').mockResolvedValue(detailRow(7, 't', []))

    const sending = store.send('make the report')
    await vi.waitFor(() => expect(chatRequested).toBe(true))
    store.stop()
    releaseChat({ streamId: 'st_half' })
    await sending

    // The optimistic exchange rolls back into the draft; the server keeps its prior history
    // instead of gaining a duplicate user message + an empty assistant turn.
    expect(update).not.toHaveBeenCalled()
    expect(cancel).toHaveBeenCalledWith('st_half')
    expect(store.busy).toBe(false)
    expect(conv.turns).toHaveLength(2)
    expect(conv.draft).toBe('make the report')
  })

  it('stop after partial stream content still snapshot-saves the partial answer', async () => {
    const sseCallbacks = captureStreamCallbacks()
    const store = useAiSessionStore()
    const conv = store.newConversation()
    conv.backendId = 7
    conv.loaded = true
    conv.turns = [turn('user', 'm1'), turn('assistant', 'm2')]
    mockQuietSendBasics()
    vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'st_partial' })
    vi.spyOn(api, 'cancelAiGeneration').mockResolvedValue()
    const update = vi.spyOn(api, 'updateConversation').mockResolvedValue(detailRow(7, 't', []))

    await store.send('make the report')
    sseCallbacks.onToken?.('partial answer')
    store.stop()
    await vi.waitFor(() => expect(update).toHaveBeenCalled())

    expect(update.mock.calls[0][1].messages).toHaveLength(4)
    expect(update.mock.calls[0][1].messages.at(-1)).toMatchObject({
      role: 'assistant',
      content: 'partial answer',
    })
  })
})
