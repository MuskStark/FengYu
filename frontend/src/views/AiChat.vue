<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore } from '@/stores/aiSession'
import { makeDesktop } from '@/mf/desktop'
import { api } from '@/api/client'
import { guessPluginForFile } from '@/stores/aiSession'
import type { PluginFileRef } from '@/api/types'
import { renderMarkdown } from '@/security/markdown'

const { t } = useI18n()
const ai = useAiSessionStore()
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)

function md(src: string): string {
  return renderMarkdown(src)
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

async function attachFile() {
  if (ai.busy) return
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickFile()
    if (!path) return
    const fileName = path.split(/[\\/]/).pop() ?? path
    const pluginId = guessPluginForFile(fileName)
    try {
      const ref: PluginFileRef = await api.grantRuntimeNativePath(pluginId, path, 'file', 'read')
      ai.addActiveFile(pluginId, ref)
    } catch (e) {
      ai.error = e instanceof Error ? e.message : 'Failed to attach file'
    }
  } else {
    const input = document.createElement('input')
    input.type = 'file'
    input.onchange = async () => {
      const file = input.files?.[0]
      if (!file) return
      const pluginId = guessPluginForFile(file.name)
      try {
        const ref: PluginFileRef = await api.uploadRuntimeFile(pluginId, file)
        ai.addActiveFile(pluginId, ref)
      } catch (e) {
        ai.error = e instanceof Error ? e.message : 'Failed to attach file'
      }
    }
    input.click()
  }
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

    <!-- Active files for this conversation -->
    <div v-if="ai.activeFiles.length" class="cx-conversation" style="padding: 0 16px">
      <div style="display: flex; flex-wrap: wrap; gap: 8px; align-items: center">
        <span
          v-for="entry in ai.activeFiles"
          :key="entry.ref.id"
          class="cx-chip"
          :class="{ 'cx-chip--warn': !entry.pluginId }"
          style="gap: 6px"
        >
          <i class="mdi" :class="entry.ref.kind === 'directory' ? 'mdi-folder' : 'mdi-file-outline'" />
          {{ entry.ref.name }}
          <span v-if="entry.pluginId" class="cx-muted" style="font-size: 11px">[{{ entry.pluginId }}]</span>
          <span v-else class="cx-muted" style="font-size: 11px">[{{ $t('aichat.selectPlugin') }}]</span>
          <button class="cx-iconbtn cx-iconbtn--sm" @click="ai.removeActiveFile(entry.pluginId, entry.ref.id)">
            <i class="mdi mdi-close" />
          </button>
        </span>
      </div>
      <div v-if="ai.activeFiles.some((f) => !f.pluginId)" class="cx-muted" style="font-size: 12px; margin-top: 4px; color: var(--md-sys-color-error)">
        {{ $t('aichat.fileNeedsPlugin') }}
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
          class="cx-iconbtn cx-iconbtn--round"
          :disabled="ai.busy"
          :title="$t('aichat.attachFile')"
          @click="attachFile"
        ><i class="mdi mdi-paperclip" /></button>
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
