<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import { useAiSessionStore } from '@/stores/aiSession'

const ai = useAiSessionStore()
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)

marked.setOptions({ breaks: true, gfm: true })

function md(src: string): string {
  return marked.parse(src) as string
}

function submit() {
  const text = draft.value
  if (!text.trim() || ai.busy) return
  draft.value = ''
  void ai.send(text)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

const hasError = computed(() => ai.error !== null)

watch(
  () => ai.turns.map((t) => t.content + t.thinking).join('|'),
  async () => {
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
)
</script>

<template>
  <div class="chat-page">
    <header class="chat-head">
      <h1 class="section-header">AI Chat</h1>
      <button class="sk-btn-secondary" @click="ai.clear()">Clear</button>
    </header>

    <div v-if="hasError" class="banner">
      {{ ai.error }}
      <button class="sk-btn-secondary retry" @click="ai.error = null">Dismiss</button>
    </div>

    <div ref="scroller" class="messages">
      <div v-if="ai.turns.length === 0" class="empty">Ask anything to get started.</div>

      <div v-for="t in ai.turns" :key="t.id" class="turn" :class="t.role">
        <div class="role">{{ t.role === 'user' ? 'You' : 'Assistant' }}</div>

        <details v-if="t.thinking" class="thinking">
          <summary>Thinking</summary>
          <div class="thinking-body" v-html="md(t.thinking)" />
        </details>

        <div v-if="t.role === 'assistant'" class="bubble" v-html="md(t.content)" />
        <div v-else class="bubble plain">{{ t.content }}</div>

        <div v-if="t.streaming && !t.content" class="typing">…</div>
      </div>
    </div>

    <div class="composer">
      <textarea
        v-model="draft"
        class="sk-field input"
        rows="2"
        placeholder="Type a message (Enter to send, Shift+Enter for newline)"
        @keydown="onKeydown"
      />
      <button v-if="ai.busy" class="sk-btn-secondary" @click="ai.stop()">Stop</button>
      <button v-else class="sk-btn-primary" :disabled="!draft.trim()" @click="submit">Send</button>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 16px 20px;
}
.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.chat-head h1 {
  margin: 0;
}
.banner {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--sk-danger-soft);
  color: var(--sk-danger);
  border: 1px solid var(--sk-danger);
  border-radius: 8px;
  padding: 8px 12px;
  margin-bottom: 8px;
}
.retry {
  margin-left: auto;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.empty {
  color: var(--sk-text-secondary);
  text-align: center;
  margin-top: 40px;
}
.turn {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 80%;
}
.turn.user {
  align-self: flex-end;
  align-items: flex-end;
}
.role {
  font-size: 11px;
  color: var(--sk-text-secondary);
}
.bubble {
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--sk-bg-elevated);
  border: 1px solid var(--sk-border);
  color: var(--sk-text);
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.turn.user .bubble {
  background: var(--sk-accent-soft);
  border-color: var(--sk-accent);
}
.bubble.plain {
  white-space: pre-wrap;
}
.thinking {
  background: var(--sk-bg-hover);
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--sk-text-secondary);
}
.thinking summary {
  cursor: pointer;
}
.thinking-body {
  margin-top: 6px;
}
.typing {
  color: var(--sk-text-secondary);
}
.composer {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 10px;
  border-top: 1px solid var(--sk-border);
}
.input {
  flex: 1;
  resize: none;
  font-family: inherit;
}
</style>
