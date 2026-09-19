import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiSessionStore } from './aiSession'
import { api } from '@/api/client'
import { openAiStream } from '@/api/sse'
vi.mock('@/api/sse', () => ({ openAiStream: vi.fn(() => ({ close: vi.fn() })) }))
beforeEach(() => { vi.restoreAllMocks(); vi.mocked(openAiStream).mockClear(); setActivePinia(createPinia()) })
it('review reproduction: two sends pass the busy guard while prepare is unresolved', async () => {
  const store = useAiSessionStore()
  const conv = store.newConversation()
  conv.scopeId = 'scope'
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  vi.spyOn(api, 'prepareChatSend').mockImplementation(async () => { await gate; return {} as any })
  const chat = vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'stream' } as any)
  const first = store.send('one')
  const second = store.send('two')
  await Promise.resolve()
  expect(store.busy).toBe(false)
  release()
  await Promise.all([first, second])
  expect(chat).toHaveBeenCalledTimes(2)
  expect(openAiStream).toHaveBeenCalledTimes(2)
})
it('review reproduction: failed persistence loses the latest turns on navigation', async () => {
  const store = useAiSessionStore()
  const conv = store.newConversation()
  conv.scopeId = 'scope'
  conv.backendId = 1
  vi.spyOn(api, 'prepareChatSend').mockResolvedValue({} as any)
  vi.spyOn(api, 'aiChat').mockResolvedValue({ streamId: 'stream' } as any)
  const update = vi.spyOn(api, 'updateConversation').mockRejectedValue(new Error('offline'))
  vi.spyOn(api, 'listChatArtifacts').mockResolvedValue([])
  await store.send('unsaved question')
  vi.mocked(openAiStream).mock.calls[0][1].onDone?.({ text: 'unsaved answer' })
  await Promise.resolve(); await Promise.resolve()
  expect(update).toHaveBeenCalled()
  expect(conv.turns).toHaveLength(2)
  await store.select(999)
  expect(conv.turns).toHaveLength(0)
  expect(conv.loaded).toBe(false)
})
