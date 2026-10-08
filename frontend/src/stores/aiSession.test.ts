import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAiSessionStore } from './aiSession'

vi.mock('@/services', () => ({
  services: {
    chat: {
      listConversations: vi.fn(),
      getConversation: vi.fn(),
      createConversation: vi.fn(),
      updateConversation: vi.fn(),
      deleteConversation: vi.fn(),
      createChatScope: vi.fn(),
      closeChatScope: vi.fn(),
      bindChatScopeConversation: vi.fn(),
      prepareChatSend: vi.fn(),
      abortChatSend: vi.fn(),
      getChatSendStatus: vi.fn(),
      send: vi.fn(),
      cancelGeneration: vi.fn(),
      listChatArtifacts: vi.fn(),
      openChatStream: vi.fn(),
      setConversationWorkspace: vi.fn(),
      clearConversationWorkspace: vi.fn(),
      setChatOutputTarget: vi.fn(),
      removeChatResource: vi.fn(),
      resolveToolApproval: vi.fn(),
      answerQuestion: vi.fn(),
      discardQueuedSends: vi.fn(),
    },
  },
}))

import { services } from '@/services'
const mockedApi = { chat: vi.mocked(services.chat) }

function resetStore() {
  useAiSessionStore.setState({
    conversations: [], activeId: null, error: null, historyLoaded: false,
  })
}

describe('aiSession conversation management (React port)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetStore()
  })

  it('loads history by merging backend rows without touching live local conversations', async () => {
    const local = useAiSessionStore.getState().newConversation()
    local.title = 'kept'
    mockedApi.chat.listConversations!.mockResolvedValue([
      { id: 11, title: 'remote', createdAt: '2026-09-23T10:00:00Z', updatedAt: '2026-09-23T11:00:00Z', workspaceRoot: '/work/api' },
    ])

    await useAiSessionStore.getState().loadHistory()

    const conversations = useAiSessionStore.getState().conversations
    expect(conversations).toHaveLength(2)
    const remote = conversations.find(conv => conv.backendId === 11)
    expect(remote?.title).toBe('remote')
    expect(remote?.loaded).toBe(false)
    expect(remote?.workspaceRoot).toBe('/work/api')
    expect(remote?.updatedAt).toBe(Date.parse('2026-09-23T11:00:00Z'))
    expect(conversations.find(conv => conv.id === local.id)?.title).toBe('kept')
    expect(useAiSessionStore.getState().historyLoaded).toBe(true)
  })

  it('lazy-loads turns on select and keeps the load failure surfaced', async () => {
    mockedApi.chat.listConversations!.mockResolvedValue([
      { id: 21, title: 'history', createdAt: '2026-09-23T10:00:00Z', updatedAt: '2026-09-23T10:00:00Z' },
    ])
    await useAiSessionStore.getState().loadHistory()
    const id = useAiSessionStore.getState().conversations[0].id

    mockedApi.chat.getConversation!.mockResolvedValue({
      id: 21, title: 'history', createdAt: '', updatedAt: '', workspaceRoot: null,
      messages: [
        { role: 'user', content: 'hi', thinking: '' },
        { role: 'assistant', content: 'hello', thinking: 'deep' },
      ],
    } as never)
    await useAiSessionStore.getState().select(id)

    const conv = useAiSessionStore.getState().conversations[0]
    expect(conv.loaded).toBe(true)
    expect(conv.turns.map(turn => turn.role)).toEqual(['user', 'assistant'])
    expect(conv.turns[1].thinking).toBe('deep')
    expect(useAiSessionStore.getState().activeId).toBe(id)

    mockedApi.chat.getConversation!.mockRejectedValue(new Error('boom'))
    const other = useAiSessionStore.getState().newConversation()
    other.backendId = 99
    other.loaded = false
    await useAiSessionStore.getState().select(other.id)
    expect(useAiSessionStore.getState().error).toContain('Failed to load this conversation')
  })

  it('reuses a provably blank conversation for new chat', () => {
    const blank = useAiSessionStore.getState().newConversation()
    const reused = useAiSessionStore.getState().newChat()
    expect(reused.id).toBe(blank.id)

    blank.draft = 'half-written'
    const fresh = useAiSessionStore.getState().newChat()
    expect(fresh.id).not.toBe(blank.id)
  })

  it('never reuses a conversation carrying a workspace or mentions', () => {
    const conv = useAiSessionStore.getState().newConversation()
    conv.draftMentions = [{
      id: 'skill:x', category: 'skill', label: 'x', description: '', value: 'x',
      markdown: '$x', icon: 'wand',
    }]
    const fresh = useAiSessionStore.getState().newChat()
    expect(fresh.id).not.toBe(conv.id)
  })

  it('attaches a workspace root through the persistence API', async () => {
    const conv = useAiSessionStore.getState().newConversation()
    mockedApi.chat.createConversation!.mockResolvedValue({ id: 77, title: '', messages: [], createdAt: '', updatedAt: '' } as never)
    mockedApi.chat.setConversationWorkspace!.mockResolvedValue({ workspaceRoot: '/work/api' })

    await useAiSessionStore.getState().setWorkspace(conv, '/work/api')

    expect(mockedApi.chat.createConversation).toHaveBeenCalled()
    expect(conv.backendId).toBe(77)
    expect(conv.workspaceRoot).toBe('/work/api')
  })

  it('deleting the active conversation falls back to a sibling or a fresh chat', async () => {
    mockedApi.chat.deleteConversation!.mockResolvedValue(undefined as never)
    const first = useAiSessionStore.getState().newConversation()
    const second = useAiSessionStore.getState().newConversation()
    expect(useAiSessionStore.getState().activeId).toBe(second.id)

    await useAiSessionStore.getState().removeConversation(second.id)
    expect(useAiSessionStore.getState().activeId).toBe(first.id)

    await useAiSessionStore.getState().removeConversation(first.id)
    expect(useAiSessionStore.getState().conversations).toHaveLength(1) // fresh blank chat
    expect(useAiSessionStore.getState().activeId).not.toBeNull()
  })

  it('caps inline images at four across pastes and flags the overflow', () => {
    const conv = useAiSessionStore.getState().newConversation()
    const img = (name: string) => ({ name, mimeType: 'image/png', base64Data: 'x' })
    useAiSessionStore.getState().attachImages(conv, [img('1'), img('2'), img('3')])
    expect(conv.draftAttachments).toHaveLength(3)

    useAiSessionStore.getState().attachImages(conv, [img('4'), img('5'), img('6')])

    expect(conv.draftAttachments).toHaveLength(4)
    expect(useAiSessionStore.getState().error).toBeTruthy()
  })

  it('editFromTurn truncates the tail and seeds the composer with the edited text', () => {
    const conv = useAiSessionStore.getState().newConversation()
    conv.turns.push(
      { id: 1, role: 'user', content: 'first', thinking: '', streaming: false, confirmations: [], activities: [], attachments: [], artifacts: [] },
      { id: 2, role: 'assistant', content: 'answer', thinking: '', streaming: false, confirmations: [], activities: [], attachments: [], artifacts: [] },
    )
    // The node test env has no DOM: stub just enough of it to capture the seed event.
    const dispatched: Array<string | undefined> = []
    vi.stubGlobal('CustomEvent', class {
      constructor(public type: string, public init?: { detail?: { text?: string } }) {}
    })
    vi.stubGlobal('window', {
      dispatchEvent: (event: { init?: { detail?: { text?: string } } }) => {
        dispatched.push(event.init?.detail?.text)
      },
    })
    try {
      useAiSessionStore.getState().editFromTurn(conv, 1)
    } finally {
      vi.unstubAllGlobals()
    }

    expect(conv.turns).toHaveLength(0)
    expect(conv.draft).toBe('first')
    expect(dispatched).toEqual(['first'])
  })
})

describe('aiSession streaming sessions (per-conversation, 4.1.0)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAiSessionStore.setState({
      conversations: [], activeId: null, error: null, historyLoaded: false,
    })
  })

  /** Resolves every service call a send needs; streamIds increment per POST. */
  function mockStreamingPipeline(streamIds: string[]) {
    let post = 0
    mockedApi.chat.createChatScope!.mockResolvedValue('scope-1')
    mockedApi.chat.prepareChatSend!.mockResolvedValue(
      { sendId: 'send-1', attachments: [], state: 'committed' })
    mockedApi.chat.send!.mockImplementation(async () => ({
      streamId: streamIds[Math.min(post++, streamIds.length - 1)],
      activeFileRefs: [],
      queued: false,
    }))
    mockedApi.chat.cancelGeneration!.mockResolvedValue(undefined)
    mockedApi.chat.closeChatScope!.mockResolvedValue(undefined)
    mockedApi.chat.discardQueuedSends!.mockResolvedValue(undefined)
    mockedApi.chat.createConversation!.mockResolvedValue(
      { id: 42, messages: [], title: '', createdAt: '', updatedAt: '' })
    mockedApi.chat.bindChatScopeConversation!.mockResolvedValue(undefined)
    mockedApi.chat.openChatStream!.mockReturnValue({ close: vi.fn() })
  }

  async function send(conv: ReturnType<typeof useAiSessionStore.getState>['conversations'][number], text: string) {
    await useAiSessionStore.getState().sendTo(conv, text)
  }

  it('streams two conversations in parallel instead of rejecting the second', async () => {
    mockStreamingPipeline(['s-a', 's-b'])
    const store = useAiSessionStore.getState()
    const a = store.newConversation()
    const b = store.newConversation()

    await Promise.all([send(a, 'hello A'), send(b, 'hello B')])

    expect(mockedApi.chat.openChatStream).toHaveBeenCalledTimes(2)
    const rows = useAiSessionStore.getState().conversations
    expect(rows.find(c => c.id === a.id)?.streaming).toBe(true)
    expect(rows.find(c => c.id === b.id)?.streaming).toBe(true)
    // Both conversations keep their optimistic turn pair — neither was rolled back.
    expect(rows.find(c => c.id === a.id)?.turns).toHaveLength(2)
    expect(rows.find(c => c.id === b.id)?.turns).toHaveLength(2)
    expect(useAiSessionStore.getState().error).toBeNull()
  })

  it('stop(conv) cancels only the target conversation and leaves parallel streams alone', async () => {
    mockStreamingPipeline(['s-a', 's-b'])
    const store = useAiSessionStore.getState()
    const a = store.newConversation()
    const b = store.newConversation()
    await Promise.all([send(a, 'hello A'), send(b, 'hello B')])

    useAiSessionStore.getState().stop(a)

    expect(mockedApi.chat.cancelGeneration).toHaveBeenCalledTimes(1)
    expect(mockedApi.chat.cancelGeneration).toHaveBeenCalledWith('s-a')
    const rows = useAiSessionStore.getState().conversations
    expect(rows.find(c => c.id === a.id)?.streaming).toBe(false)
    expect(rows.find(c => c.id === a.id)?.turns.at(-1)?.streaming).toBe(false)
    expect(rows.find(c => c.id === b.id)?.streaming).toBe(true)
  })

  it('removing a streaming conversation stops its stream before deleting the row', async () => {
    mockStreamingPipeline(['s-a'])
    const store = useAiSessionStore.getState()
    const a = store.newConversation()
    await send(a, 'hello')

    await useAiSessionStore.getState().removeConversation(a.id)

    expect(mockedApi.chat.cancelGeneration).toHaveBeenCalledWith('s-a')
    expect(useAiSessionStore.getState().conversations.find(c => c.id === a.id)).toBeUndefined()
  })

  it('a stopped send\'s late POST cannot hijack the resend\'s session', async () => {
    // Regression (review finding): epochs used to restart at 1 after a session delete,
    // so a stopped send's in-flight POST resolved AFTER a resend and read the new
    // session as its own — double streams, an unstoppable orphan, a stuck flag.
    mockStreamingPipeline(['s-b', 's-b'])
    type ChatStartResponse = import('@/services/types').ChatStartResponse
    const resolvers: Array<(value: ChatStartResponse) => void> = []
    const firstPost = new Promise<ChatStartResponse>(resolve => { resolvers.push(resolve) })
    let sendCalls = 0
    mockedApi.chat.send!.mockImplementation(async () => {
      sendCalls += 1
      // First send's POST hangs until the test resolves it — after the stop + resend.
      if (sendCalls === 1) return firstPost
      return { streamId: 's-b', activeFileRefs: [], queued: false } as ChatStartResponse
    })
    const store = useAiSessionStore.getState()
    const a = store.newConversation()
    // The node test env has no DOM: stub just enough for the rollback path's
    // composer-seed dispatch (same shape as the editFromTurn test below).
    const dispatched: Array<string | undefined> = []
    vi.stubGlobal('CustomEvent', class {
      constructor(public type: string, public init?: { detail?: { text?: string } }) {}
    })
    vi.stubGlobal('window', {
      dispatchEvent: (event: { init?: { detail?: { text?: string } } }) => {
        dispatched.push(event.init?.detail?.text)
      },
      setTimeout: (fn: () => void) => { fn(); return 0 },
    })

    try {
      const first = store.sendTo(a, 'first')
      await vi.waitFor(() => expect(sendCalls).toBeGreaterThanOrEqual(1))
      useAiSessionStore.getState().stop(a)
      const second = useAiSessionStore.getState().sendTo(a, 'second')
      await vi.waitFor(() => expect(sendCalls).toBe(2))
      // The FIRST send's POST lands now — after the stop and after the resend began.
      resolvers[0]?.({ streamId: 's-a', activeFileRefs: [], queued: false })
      await Promise.all([first, second])

      // The orphaned turn was cancelled server-side and never opened locally…
      expect(mockedApi.chat.cancelGeneration).toHaveBeenCalledWith('s-a')
      expect(mockedApi.chat.openChatStream).toHaveBeenCalledTimes(1)
      expect(mockedApi.chat.openChatStream).toHaveBeenCalledWith('s-b',
        expect.objectContaining({ onToken: expect.any(Function) }))
      // …and the resend owns the conversation: exactly its turn pair, still streaming.
      const row = useAiSessionStore.getState().conversations.find(c => c.id === a.id)
      expect(row?.streaming).toBe(true)
      expect(row?.turns.map(turn => turn.role)).toEqual(['user', 'assistant'])
      expect(row?.turns[0].content).toBe('second')
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
