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
    },
  },
}))

import { services } from '@/services'
const mockedApi = { chat: vi.mocked(services.chat) }

function resetStore() {
  useAiSessionStore.setState({
    conversations: [], activeId: null, busy: false, error: null, historyLoaded: false,
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
})
