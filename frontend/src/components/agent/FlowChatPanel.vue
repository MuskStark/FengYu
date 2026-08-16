<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { openAiStream, type SseHandle } from '@/api/sse'
import type { AiPermissionMode, ChatMessage } from '@/api/types'

/**
 * Flowise-style builder chat: a docked conversation bound to the flow being
 * edited. Each turn posts the history with the flow's id, and the model can
 * invoke the flow through `run_current_flow` — an ordinary tool call in the
 * same chat tool-call loop (approval gates included) that powers AI Chat.
 */

interface ToolActivity {
  id: string
  name: string
  phase: 'call' | 'approval_required' | 'result'
  success?: boolean
  approvalId?: string
  resolved?: boolean
  output?: string
}

interface ChatTurn {
  role: 'user' | 'assistant'
  content: string
  tools?: ToolActivity[]
}

const props = defineProps<{
  workflowId: string | null
  workflowTitle: string
  disabled?: boolean
  /** Runs before a turn is sent (the builder auto-saves the graph); false aborts. */
  prepare?: () => Promise<boolean>
}>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
const turns = ref<ChatTurn[]>([])
const input = ref('')
const permissionMode = ref<AiPermissionMode>('ask-for-approval')
const busy = ref(false)
const errorMsg = ref<string | null>(null)
const listEl = ref<HTMLElement | null>(null)
let stream: SseHandle | null = null
let streamId: string | null = null

const canSend = computed(() =>
  !props.disabled && !busy.value && !!props.workflowId && input.value.trim().length > 0)

/** History for the next request — the Flowise pattern: context rides each call. */
function toHistory(): ChatMessage[] {
  return turns.value
    .filter((turn) => turn.content.trim() || turn.role === 'user')
    .map((turn) => ({ role: turn.role, content: turn.content }))
}

function scrollToBottom() {
  void nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

function toolOf(turn: ChatTurn, id: string): ToolActivity | undefined {
  return turn.tools?.find((activity) => activity.id === id)
}

function applyToolEvent(payload: Record<string, unknown>) {
  const last = turns.value.at(-1)
  if (!last || last.role !== 'assistant') return
  const id = String(payload.id ?? '')
  const name = String(payload.name ?? '')
  const phase = String(payload.phase ?? '')
  if (!id) return
  let activity = toolOf(last, id)
  if (!activity) {
    activity = { id, name, phase: 'call' }
    last.tools = [...(last.tools ?? []), activity]
  }
  if (phase === 'call') {
    activity.phase = 'call'
  } else if (phase === 'approval_required') {
    activity.phase = 'approval_required'
    activity.approvalId = payload.approvalId ? String(payload.approvalId) : undefined
    activity.resolved = false
  } else if (phase === 'result') {
    activity.phase = 'result'
    activity.success = payload.success !== false
    activity.output = typeof payload.output === 'string' ? payload.output : undefined
  }
}

async function resolveApproval(activity: ToolActivity, approved: boolean) {
  if (!activity.approvalId || activity.resolved) return
  activity.resolved = true
  try {
    await api.resolveAiToolApproval(activity.approvalId, approved)
  } catch (e) {
    activity.resolved = false
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || busy.value) return
  if (!props.workflowId) {
    errorMsg.value = t('flows.chatNeedSave')
    return
  }
  if (props.prepare && !await props.prepare()) return
  input.value = ''
  errorMsg.value = null
  turns.value = [...turns.value, { role: 'user', content: text }, { role: 'assistant', content: '', tools: [] }]
  busy.value = true
  scrollToBottom()
  try {
    const start = await api.aiChat(toHistory(), undefined, permissionMode.value, props.workflowId)
    streamId = start.streamId
    stream = openAiStream(start.streamId, {
      onToken: (token) => {
        const last = turns.value.at(-1)
        if (last && last.role === 'assistant') last.content += token
        scrollToBottom()
      },
      onTool: (payload) => {
        applyToolEvent(payload)
        scrollToBottom()
      },
      onDone: () => {
        busy.value = false
        stream = null
        streamId = null
      },
      onError: (message) => {
        errorMsg.value = message
        busy.value = false
        stream = null
        streamId = null
      },
    })
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    busy.value = false
  }
}

function stop() {
  if (streamId) void api.cancelAiGeneration(streamId)
  stream?.close()
  stream = null
  streamId = null
  busy.value = false
}

// The panel mounts/unmounts via v-if — closing the dock mid-generation must cancel
// the backend generation too, or the EventSource leaks and the stream keeps running
// unseen. stop() is idempotent (null guards on streamId/stream).
onBeforeUnmount(() => {
  if (busy.value) stop()
})

function clearConversation() {
  if (busy.value) stop()
  turns.value = []
  errorMsg.value = null
}

defineExpose({ clearConversation })
</script>

<template>
  <div class="flow-chat">
    <div class="flow-chat__head">
      <span class="flow-chat__title">
        <i class="mdi mdi-comment-processing-outline" />
        {{ t('flows.chatTitle') }}
        <small>{{ workflowTitle }}</small>
      </span>
      <span class="cx-row">
        <button
          v-if="turns.length"
          class="cx-iconbtn cx-iconbtn--sm"
          :title="t('flows.chatClear')"
          :disabled="busy"
          @click="clearConversation"
        ><i class="mdi mdi-broom" /></button>
        <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('flows.close')" @click="emit('close')"><i class="mdi mdi-close" /></button>
      </span>
    </div>

    <div v-if="!workflowId" class="flow-chat__hint">
      <i class="mdi mdi-information-outline" />
      {{ t('flows.chatNeedSave') }}
    </div>
    <div v-else class="flow-chat__hint flow-chat__hint--intro">
      <i class="mdi mdi-robot-outline" />
      {{ t('flows.chatIntro') }}
    </div>

    <div ref="listEl" class="flow-chat__list">
      <div v-for="(turn, index) in turns" :key="index" class="flow-chat__turn" :class="`flow-chat__turn--${turn.role}`">
        <div v-if="turn.role === 'user'" class="flow-chat__bubble flow-chat__bubble--user">{{ turn.content }}</div>
        <div v-else class="flow-chat__bubble flow-chat__bubble--assistant">
          <div v-for="activity in turn.tools" :key="activity.id" class="flow-chat__tool" :class="{ 'flow-chat__tool--failed': activity.phase === 'result' && activity.success === false }">
            <i class="mdi" :class="activity.phase === 'result'
              ? (activity.success === false ? 'mdi-close-circle-outline' : 'mdi-check-circle-outline')
              : activity.phase === 'approval_required' ? 'mdi-clock-outline' : 'mdi-tools'" />
            <span class="flow-chat__tool-name">{{ activity.name }}</span>
            <span v-if="activity.phase === 'result' && activity.output" class="flow-chat__tool-output" :title="activity.output">{{ activity.output.slice(0, 140) }}</span>
            <template v-if="activity.phase === 'approval_required' && !activity.resolved && activity.approvalId">
              <button class="cx-btn cx-btn--primary flow-chat__approve" @click="resolveApproval(activity, true)">{{ t('flows.chatApprove') }}</button>
              <button class="cx-btn cx-btn--outline flow-chat__deny" @click="resolveApproval(activity, false)">{{ t('flows.chatDeny') }}</button>
            </template>
            <span v-else-if="activity.phase === 'approval_required'" class="flow-chat__tool-status">{{ activity.resolved ? t('flows.chatApprovalHandled') : '' }}</span>
          </div>
          <div v-if="turn.content" class="flow-chat__text">{{ turn.content }}</div>
          <div v-else-if="busy && index === turns.length - 1" class="flow-chat__typing"><span class="cx-spin" /> {{ t('flows.chatThinking') }}</div>
        </div>
      </div>
    </div>

    <div v-if="errorMsg" class="cx-alert cx-alert--error flow-chat__error">
      <span class="cx-alert__body">{{ errorMsg }}</span>
      <button class="cx-iconbtn cx-iconbtn--sm" @click="errorMsg = null"><i class="mdi mdi-close" /></button>
    </div>

    <div class="flow-chat__composer">
      <select v-model="permissionMode" class="cx-select" :disabled="busy">
        <option value="ask-for-approval">{{ t('aichat.permissionAsk') }}</option>
        <option value="approve-for-me">{{ t('aichat.permissionAuto') }}</option>
        <option value="full-access">{{ t('aichat.permissionFullAccess') }}</option>
      </select>
      <textarea
        v-model="input"
        rows="1"
        class="flow-chat__input"
        :placeholder="t('flows.chatPlaceholder')"
        :disabled="busy || disabled || !workflowId"
        @keydown.enter.exact.prevent="send"
      />
      <button
        v-if="busy"
        class="cx-iconbtn cx-iconbtn--sm"
        :title="t('agent.cancel')"
        @click="stop"
      ><i class="mdi mdi-stop" /></button>
      <button
        v-else
        class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--sm"
        :disabled="!canSend"
        :title="t('agent.run')"
        @click="send"
      ><i class="mdi mdi-send" /></button>
    </div>
  </div>
</template>

<style scoped>
.flow-chat {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.flow-chat__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
}

.flow-chat__title {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.flow-chat__title i { color: rgb(var(--v-theme-primary)); }
.flow-chat__title small {
  overflow: hidden;
  color: rgba(var(--v-theme-on-surface), .55);
  font-size: 10px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flow-chat__hint {
  display: flex;
  gap: 7px;
  align-items: flex-start;
  margin-bottom: 8px;
  padding: 7px 9px;
  color: rgba(var(--v-theme-on-surface), .68);
  font-size: 10px;
  line-height: 1.45;
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), .07);
}

.flow-chat__hint i { flex: 0 0 auto; color: rgb(var(--v-theme-primary)); font-size: 13px; }

.flow-chat__list {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 9px;
  min-height: 120px;
  max-height: 44vh;
  margin-bottom: 9px;
  padding: 2px;
  overflow-y: auto;
}

.flow-chat__turn { display: flex; flex-direction: column; }
.flow-chat__turn--user { align-items: flex-end; }
.flow-chat__turn--assistant { align-items: flex-start; }

.flow-chat__bubble {
  max-width: 94%;
  padding: 7px 10px;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 11px;
}

.flow-chat__bubble--user {
  color: rgb(var(--v-theme-on-primary));
  border-bottom-right-radius: 4px;
  background: rgb(var(--v-theme-primary));
  white-space: pre-wrap;
}

.flow-chat__bubble--assistant {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
  width: 100%;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-bottom-left-radius: 4px;
  background: rgb(var(--v-theme-surface-variant));
}

.flow-chat__text { padding: 2px 4px; white-space: pre-wrap; overflow-wrap: anywhere; }

.flow-chat__tool {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 4px 7px;
  font-size: 10px;
  border-radius: 7px;
  background: rgba(var(--v-theme-primary), .08);
}

.flow-chat__tool--failed { background: rgba(var(--v-theme-error), .1); }
.flow-chat__tool--failed i { color: rgb(var(--v-theme-error)); }
.flow-chat__tool i { color: rgb(var(--v-theme-primary)); }
.flow-chat__tool-name { font-family: var(--mono-font, monospace); font-weight: 600; }
.flow-chat__tool-output {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  color: rgba(var(--v-theme-on-surface), .6);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.flow-chat__tool-status { color: rgba(var(--v-theme-on-surface), .55); }
.flow-chat__approve, .flow-chat__deny { min-height: 22px; padding: 1px 8px; font-size: 10px; }

.flow-chat__typing {
  display: flex;
  gap: 7px;
  align-items: center;
  color: rgba(var(--v-theme-on-surface), .55);
  font-size: 10px;
}

.flow-chat__error { margin-bottom: 8px; }

.flow-chat__composer {
  display: flex;
  gap: 6px;
  align-items: flex-end;
}

.flow-chat__composer .cx-select { width: 128px; font-size: 10px; }

.flow-chat__input {
  flex: 1;
  min-height: 34px;
  max-height: 110px;
  padding: 7px 10px;
  color: inherit;
  font: inherit;
  font-size: 12px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 9px;
  outline: 0;
  resize: none;
  background: rgb(var(--v-theme-surface-container));
}

.flow-chat__input:focus { border-color: rgb(var(--v-theme-primary)); }
</style>
