import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { api } from '@/api/client'
import { openAiStream, type SseHandle } from '@/api/sse'
import type { ChatMessage } from '@/api/types'

export interface ChatTurn {
  id: number
  role: 'user' | 'assistant'
  content: string
  thinking: string
  streaming: boolean
}

export const useAiSessionStore = defineStore('aiSession', () => {
  const turns = ref<ChatTurn[]>([])
  const busy = ref(false)
  const error = ref<string | null>(null)
  let seq = 0
  let handle: SseHandle | null = null

  function history(): ChatMessage[] {
    return turns.value.map((t) => ({ role: t.role, content: t.content }))
  }

  async function send(text: string) {
    const prompt = text.trim()
    if (!prompt || busy.value) return
    error.value = null

    turns.value.push({ id: ++seq, role: 'user', content: prompt, thinking: '', streaming: false })
    // Must be reactive() so the closures below mutate the PROXY, not the raw object.
    // turns.value.push(obj) stores the raw object; keeping `assistant` as that raw ref
    // means assistant.content += t silently bypasses Vue's reactivity, and the UI only
    // repaints once at onDone (where a separate ref flips) — i.e. the whole text appears
    // at once instead of streaming token-by-token.
    const assistant = reactive<ChatTurn>({
      id: ++seq,
      role: 'assistant',
      content: '',
      thinking: '',
      streaming: true,
    })
    turns.value.push(assistant)
    busy.value = true

    try {
      const { streamId } = await api.aiChat(history())
      handle = openAiStream(streamId, {
        onToken: (t) => {
          assistant.content += t
        },
        onThinking: (t) => {
          assistant.thinking += t
        },
        onDone: (payload) => {
          if (payload.text && !assistant.content) assistant.content = payload.text
          assistant.streaming = false
          busy.value = false
          handle = null
        },
        onError: (message) => {
          error.value = message
          assistant.streaming = false
          busy.value = false
          handle = null
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
    busy.value = false
    const last = turns.value[turns.value.length - 1]
    if (last && last.streaming) last.streaming = false
  }

  function clear() {
    stop()
    turns.value = []
    error.value = null
  }

  return { turns, busy, error, send, stop, clear }
})
