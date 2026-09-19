import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { toChatHistory, type ChatTurn } from './aiSession'
import type { ChatResource, ChatSendStatus, ChatStartResponse, PluginDescriptor } from '@/api/types'
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
