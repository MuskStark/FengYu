<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore } from '@/stores/aiSession'

const { t } = useI18n()
const ai = useAiSessionStore()
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)

marked.setOptions({ breaks: true, gfm: true })

function md(src: string): string {
  return marked.parse(src) as string
}

function autosize() {
  const el = textarea.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function submit() {
  const text = draft.value
  if (!text.trim() || ai.busy) return
  draft.value = ''
  void nextTick(autosize)
  void ai.send(text)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

const hasError = computed(() => ai.error !== null)
const empty = computed(() => ai.turns.length === 0)

watch(
  () => ai.turns.map((turn) => turn.content + turn.thinking).join('|'),
  async () => {
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
)
watch(() => ai.activeId, async () => {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
})
</script>

<template>
  <div class="d-flex flex-column h-100" style="display: flex; flex-direction: column; height: 100%; position: relative">
    <!-- Top bar -->
    <div class="cx-topbar" style="justify-content: flex-end; border-bottom: none; min-height: 48px">
      <button v-if="!empty" class="cx-btn cx-btn--text cx-btn--sm" @click="ai.clear()">
        <i class="mdi mdi-broom" />{{ $t('aichat.clear') }}
      </button>
    </div>

    <!-- Scroll region -->
    <div ref="scroller" style="flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 0 16px">
      <!-- Empty / hero -->
      <div
        v-if="empty"
        class="cx-conversation"
        style="display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; min-height: 55vh"
      >
        <span class="cx-avatar" style="width: 46px; height: 46px; margin-bottom: 16px">
          <i class="mdi lg mdi-hexagon-multiple-outline" />
        </span>
        <div style="font-size: 20px; font-weight: 600; margin-bottom: 4px">{{ $t('aichat.heroTitle') }}</div>
        <div class="cx-muted">{{ $t('aichat.empty') }}</div>
      </div>

      <!-- Conversation -->
      <div v-else class="cx-conversation" style="padding: 16px 0">
        <div
          v-for="turn in ai.turns"
          :key="turn.id"
          class="cx-msg"
          :class="{ 'cx-msg--user': turn.role === 'user' }"
        >
          <!-- User: right-aligned chip, no role label (Codex style) -->
          <div v-if="turn.role === 'user'" class="cx-user-body">{{ turn.content }}</div>

          <!-- Assistant: flowing text with role label + optional thinking -->
          <template v-else>
            <div class="cx-msg-role">{{ t('aichat.assistant') }}</div>

            <details v-if="turn.thinking" class="cx-details" style="margin-bottom: 8px">
              <summary>{{ $t('aichat.thinking') }}</summary>
              <div class="cx-details__body cx-md cx-muted" v-html="md(turn.thinking)" />
            </details>

            <div class="cx-md" v-html="md(turn.content)" />

            <div v-for="item in turn.confirmations" :key="item.confirmationId" class="cx-card" style="margin-top: 12px; padding: 14px">
              <div style="font-weight: 650; margin-bottom: 8px">{{ $t('aichat.confirmTitle') }}</div>
              <dl style="margin: 0 0 10px; display: grid; gap: 5px">
                <div v-for="row in item.summary" :key="row.label" style="display: flex; justify-content: space-between; gap: 16px">
                  <dt class="cx-muted">{{ row.label }}</dt><dd style="margin: 0; text-align: right">{{ row.value }}</dd>
                </div>
              </dl>
              <div class="cx-muted" style="font-size: 12px; margin-bottom: 10px">{{ $t('aichat.expiresAt', { time: item.expiresAt }) }}</div>
              <div v-if="item.status === 'pending'" style="display: flex; gap: 8px">
                <button class="cx-btn cx-btn--primary cx-btn--sm" @click="ai.resolveConfirmation(item, true)">{{ $t('aichat.approveSend') }}</button>
                <button class="cx-btn cx-btn--outline cx-btn--sm" @click="ai.resolveConfirmation(item, false)">{{ $t('aichat.rejectSend') }}</button>
              </div>
              <div v-else-if="item.status === 'submitting'" class="cx-muted"><span class="cx-spin" /> {{ $t('aichat.submittingApproval') }}</div>
              <div v-else-if="item.status === 'approved'" class="cx-chip cx-chip--success">{{ $t('aichat.approved') }}</div>
              <div v-else-if="item.status === 'rejected'" class="cx-chip">{{ $t('aichat.rejected') }}</div>
              <div v-else class="cx-alert cx-alert--error">{{ item.error }}</div>
            </div>

            <div v-if="turn.streaming && !turn.content" style="margin-top: 4px">
              <span class="cx-spin" />
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- Composer -->
    <div style="padding: 8px 16px 16px">
      <div v-if="hasError" class="cx-alert cx-alert--error cx-conversation" style="margin-bottom: 8px">
        <span class="cx-alert__body">{{ ai.error }}</span>
        <button class="cx-iconbtn cx-iconbtn--sm" @click="ai.error = null"><i class="mdi mdi-close" /></button>
      </div>

      <div class="cx-composer" style="display: flex; align-items: flex-end; gap: 8px">
        <textarea
          ref="textarea"
          v-model="draft"
          rows="1"
          class="cx-grow"
          style="padding: 8px 0"
          :placeholder="$t('aichat.placeholder')"
          @input="autosize"
          @keydown="onKeydown"
        />
        <button
          v-if="ai.busy"
          class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
          :title="$t('aichat.stop')"
          @click="ai.stop()"
        ><i class="mdi mdi-stop" /></button>
        <button
          v-else
          class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
          :disabled="!draft.trim()"
          :title="$t('aichat.send')"
          @click="submit"
        ><i class="mdi mdi-arrow-up" /></button>
      </div>
      <div class="cx-conversation cx-muted" style="text-align: center; font-size: 12px; margin-top: 8px">
        {{ $t('aichat.hint') }}
      </div>
    </div>
  </div>
</template>
