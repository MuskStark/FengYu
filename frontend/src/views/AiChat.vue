<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore } from '@/stores/aiSession'
import { useSettingsStore } from '@/stores/settings'
import { makeDesktop } from '@/mf/desktop'
import { api } from '@/api/client'
import type { AiMode } from '@/api/types'
import { renderMarkdown } from '@/security/markdown'
import { composerSubmissionText } from './aiChatComposer'
import { configuredChatModels } from './aiChatModels'

const { t, locale } = useI18n()
const ai = useAiSessionStore()
const settings = useSettingsStore()
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)
const permissionMenuOpen = ref(false)
const attachMenuOpen = ref(false)
const modelMenuOpen = ref(false)
const modelSwitching = ref(false)
const listening = ref(false)
const copiedId = ref<number | null>(null)
let speechRecognition: SpeechRecognitionLike | null = null

interface SpeechRecognitionLike {
  lang: string
  interimResults: boolean
  continuous: boolean
  onresult: ((event: { results: ArrayLike<{ 0: { transcript: string } }> }) => void) | null
  onerror: (() => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
}

onMounted(() => {
  void settings.loadAi().catch(() => {
    ai.error = t('aichat.modelsLoadFailed')
  })
})

onBeforeUnmount(() => speechRecognition?.stop())

/**
 * A file/dir chosen by the user but not yet granted. Confirmation fans the selection out as
 * separate plugin-scoped grants to every compatible backend plugin; isolation is preserved while
 * the user no longer has to predict which tool the model will choose.
 */
interface PendingAttach {
  /** Desktop native path grant, or browser upload. */
  source: 'native' | 'upload'
  /** Native absolute path (source === 'native'). */
  path?: string
  /** Browser File list (source === 'upload'); length 1 for a file, many for a directory. */
  files?: File[]
  name: string
  kind: 'file' | 'directory'
}
const pendingFile = ref<PendingAttach | null>(null)
const granting = ref(false)

function md(src: string): string {
  return renderMarkdown(src)
}

/** Copy a whole message turn to the clipboard with a transient "copied" state. */
async function copyMessage(turn: { id: number; content: string }) {
  try {
    await navigator.clipboard.writeText(turn.content)
  } catch {
    /* clipboard unavailable in this context — ignore */
  }
  copiedId.value = turn.id
  window.setTimeout(() => {
    if (copiedId.value === turn.id) copiedId.value = null
  }, 1500)
}

/** Copy a rendered code block via click/keyboard delegation on the scroll region. */
async function copyCodeFromEvent(target: HTMLElement) {
  const block = target.closest('.cx-code')
  const pre = block?.querySelector('pre')
  if (!pre) return
  try {
    await navigator.clipboard.writeText(pre.textContent ?? '')
  } catch {
    /* ignore */
  }
}
function onScrollerClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (target.closest('.cx-code__copy')) {
    e.preventDefault()
    void copyCodeFromEvent(target)
  }
}
function onScrollerKeydown(e: KeyboardEvent) {
  const target = e.target as HTMLElement
  if (target.closest('.cx-code__copy') && (e.key === 'Enter' || e.key === ' ')) {
    e.preventDefault()
    void copyCodeFromEvent(target)
  }
}

function autosize() {
  const el = textarea.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function submit() {
  // Vue deliberately suppresses v-model updates while an IME composition is active. Reading the
  // DOM value here preserves the just-committed Latin suffix when the user clicks Send directly.
  const text = composerSubmissionText(draft.value, textarea.value?.value)
  if (!text.trim() || ai.busy || modelSwitching.value) return
  if (!activeModel.value) {
    ai.error = t('aichat.noConfiguredModels')
    return
  }
  draft.value = ''
  void nextTick(autosize)
  void ai.send(text)
}

/** Step 1 of the two-step attach: pick a FILE. Stores it as pending WITHOUT granting. */
async function attachFile() {
  if (ai.busy || pendingFile.value) return
  attachMenuOpen.value = false
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickFile()
    if (!path) return
    const fileName = path.split(/[\\/]/).pop() ?? path
    startPending({ source: 'native', path, name: fileName, kind: 'file' })
  } else {
    const input = document.createElement('input')
    input.type = 'file'
    input.onchange = () => {
      const file = input.files?.[0]
      if (!file) return
      startPending({ source: 'upload', files: [file], name: file.name, kind: 'file' })
    }
    input.click()
  }
}

/** Step 1 of the two-step attach: pick a DIRECTORY. Stores it as pending WITHOUT granting. */
async function attachDirectory() {
  if (ai.busy || pendingFile.value) return
  attachMenuOpen.value = false
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickDirectory()
    if (!path) return
    const dirName = path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? path
    startPending({ source: 'native', path, name: dirName, kind: 'directory' })
  } else {
    const input = document.createElement('input')
    input.type = 'file'
    input.setAttribute('webkitdirectory', '')
    input.multiple = true
    input.onchange = () => {
      const files = input.files ? Array.from(input.files) : []
      if (files.length === 0) return
      // webkitRelativePath is "topdir/..."; the common top directory names the entry.
      const top = files[0].webkitRelativePath.split('/')[0] || files[0].name
      startPending({ source: 'upload', files, name: top, kind: 'directory' })
    }
    input.click()
  }
}

function startPending(entry: PendingAttach) {
  pendingFile.value = entry
}

/** Step 2: create an isolated grant for every compatible backend plugin. */
async function confirmPending() {
  const entry = pendingFile.value
  if (!entry || granting.value) return
  granting.value = true
  try {
    let refs
    if (entry.source === 'native') {
      refs = await api.grantAiNativePath(entry.path!, entry.kind)
    } else if (entry.kind === 'directory') {
      refs = await api.uploadAiDirectory(entry.files!)
    } else {
      refs = await api.uploadAiFile(entry.files![0])
    }
    if (refs.length === 0) throw new Error(t('aichat.fileNeedsPlugin'))
    for (const item of refs) ai.addActiveFile(item.pluginId, item.ref)
    pendingFile.value = null
  } catch (e) {
    const fallback = entry.kind === 'directory' ? t('aichat.attachDirectoryFailed') : t('aichat.attachFileFailed')
    ai.error = e instanceof Error ? e.message : fallback
  } finally {
    granting.value = false
  }
}

function cancelPending() {
  pendingFile.value = null
}

function onKeydown(e: KeyboardEvent) {
  if (e.isComposing || composing.value || e.keyCode === 229) return
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

function onCompositionEnd(e: CompositionEvent) {
  composing.value = false
  draft.value = (e.target as HTMLTextAreaElement).value
}

const hasError = computed(() => ai.error !== null)
const empty = computed(() => ai.turns.length === 0)
const activeTitle = computed(() => {
  const c = ai.conversations.find((x) => x.id === ai.activeId)
  return c?.title || ''
})
const modelOptions = computed(() => configuredChatModels(settings.aiSettings))
const activeModel = computed(() => modelOptions.value.find(
  option => option.mode === (settings.aiSettings?.activeMode ?? settings.aiSettings?.mode),
) ?? null)
const composerConfirmations = computed(() => ai.turns.flatMap(turn => turn.confirmations)
  .filter(item => ['pending', 'submitting', 'error'].includes(item.status)))
const permissionOptions = computed(() => [
  { id: 'ask-for-approval' as const, icon: 'mdi-hand-back-left-outline', title: t('aichat.permissionAsk'), description: t('aichat.permissionAskHint') },
  { id: 'approve-for-me' as const, icon: 'mdi-shield-check-outline', title: t('aichat.permissionAuto'), description: t('aichat.permissionAutoHint') },
  { id: 'full-access' as const, icon: 'mdi-shield-alert-outline', title: t('aichat.permissionFullAccess'), description: t('aichat.permissionFullHint') },
])

function activityIcon(status: string): string {
  if (status === 'completed') return 'mdi-check'
  if (status === 'failed' || status === 'rejected') return 'mdi-close'
  if (status === 'waiting') return 'mdi-shield-outline'
  return 'mdi-loading mdi-spin'
}

function selectPermissionMode(mode: typeof ai.permissionMode) {
  if (ai.busy) return
  ai.permissionMode = mode
  permissionMenuOpen.value = false
}

async function selectModel(mode: AiMode) {
  if (modelSwitching.value || mode === settings.aiSettings?.activeMode) {
    modelMenuOpen.value = false
    return
  }
  modelSwitching.value = true
  try {
    await settings.updateAi({ mode })
    modelMenuOpen.value = false
  } catch (error) {
    ai.error = error instanceof Error ? error.message : t('aichat.modelSwitchFailed')
  } finally {
    modelSwitching.value = false
  }
}

function toggleVoiceInput() {
  if (speechRecognition && listening.value) {
    speechRecognition.stop()
    return
  }
  const speechWindow = window as typeof window & {
    SpeechRecognition?: new () => SpeechRecognitionLike
    webkitSpeechRecognition?: new () => SpeechRecognitionLike
  }
  const Recognition = speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition
  if (!Recognition) {
    ai.error = t('aichat.voiceUnavailable')
    return
  }
  const recognition = new Recognition()
  speechRecognition = recognition
  recognition.lang = locale.value.startsWith('zh') ? 'zh-CN' : 'en-US'
  recognition.interimResults = false
  recognition.continuous = false
  recognition.onresult = (event) => {
    const transcript = event.results[0]?.[0]?.transcript?.trim()
    if (transcript) draft.value = `${draft.value}${draft.value ? ' ' : ''}${transcript}`
    void nextTick(autosize)
  }
  recognition.onerror = () => { listening.value = false }
  recognition.onend = () => {
    listening.value = false
    speechRecognition = null
  }
  listening.value = true
  recognition.start()
}

watch(
  () => ai.turns.map((turn) => turn.content + turn.thinking
    + turn.confirmations.map((item) => `${item.confirmationId}:${item.status}`).join(',')
    + turn.activities.map((item) => `${item.id}:${item.status}`).join(',')).join('|'),
  async () => {
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
)
watch(() => ai.activeId, async () => {
  // A pending attach was started in another conversation; clear it so the grant
  // doesn't land in the wrong conversation's activeFiles when confirmed here.
  cancelPending()
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
})
</script>

<template>
  <div class="d-flex flex-column h-100" style="display: flex; flex-direction: column; height: 100%; position: relative">
    <!-- Top bar -->
    <div class="cx-topbar" style="border-bottom: none; min-height: 48px">
      <span
        v-if="!empty && activeTitle"
        class="cx-muted"
        style="font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: inline-flex; align-items: center; gap: 7px"
      >
        <i class="mdi mdi-chevron-right" style="opacity: .5" />{{ activeTitle }}
      </span>
      <div style="flex: 1 1 auto"></div>
      <button v-if="!empty" class="cx-btn cx-btn--text cx-btn--sm" @click="ai.clear()">
        <i class="mdi mdi-broom" />{{ $t('aichat.clear') }}
      </button>
    </div>

    <!-- Scroll region -->
    <div
      ref="scroller"
      style="flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 0 16px"
      @click="onScrollerClick"
      @keydown="onScrollerKeydown"
    >
      <!-- Empty / hero -->
      <div
        v-if="empty"
        class="cx-conversation"
        style="display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; min-height: 55vh"
      >
        <span class="cx-avatar ai-empty-avatar" aria-hidden="true">
          <img src="/infinia-logo.svg" alt="" />
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
          <!-- User: full-width tinted block (block style) -->
          <div v-if="turn.role === 'user'" class="cx-msg-body">{{ turn.content }}</div>
          <div v-if="turn.role === 'user'" class="cx-msg-actions">
            <button class="cx-msg-action" @click="copyMessage(turn)">
              <i class="mdi" :class="copiedId === turn.id ? 'mdi-check' : 'mdi-content-copy'" />
              {{ copiedId === turn.id ? $t('aichat.copied') : $t('aichat.copy') }}
            </button>
          </div>

          <!-- Assistant: flowing text with role label + optional thinking -->
          <template v-else>
            <div class="cx-msg-role">{{ t('aichat.assistant') }}</div>

            <details v-if="turn.thinking" class="cx-details" style="margin-bottom: 8px">
              <summary>{{ $t('aichat.thinking') }}</summary>
              <div class="cx-details__body cx-md cx-muted" v-html="md(turn.thinking)" />
            </details>

            <div v-if="turn.activities.length" style="display: grid; gap: 5px; margin: 6px 0 10px">
              <div v-for="activity in turn.activities" :key="activity.id" class="cx-muted" style="display: flex; gap: 8px; align-items: center; font-size: 13px">
                <i class="mdi" :class="activityIcon(activity.status)" />
                <span style="color: rgb(var(--v-theme-on-surface))">{{ activity.label }}</span>
                <span v-if="activity.status === 'waiting'">{{ $t('aichat.awaitingApproval') }}</span>
                <span v-else-if="activity.status === 'failed'">{{ $t('aichat.toolFailed') }}</span>
              </div>
            </div>

            <!-- Approval prompts live in the composer; the transcript keeps only compact activity rows. -->
            <div class="cx-md" v-html="md(turn.content)" />

            <div v-if="turn.streaming && !turn.content" style="margin-top: 4px">
              <span class="cx-spin" />
            </div>
            <div v-if="!turn.streaming && turn.content" class="cx-msg-actions">
              <button class="cx-msg-action" @click="copyMessage(turn)">
                <i class="mdi" :class="copiedId === turn.id ? 'mdi-check' : 'mdi-content-copy'" />
                {{ copiedId === turn.id ? $t('aichat.copied') : $t('aichat.copy') }}
              </button>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- Pending attach: one confirmation creates separate grants for compatible backend plugins. -->
    <div v-if="pendingFile" class="cx-conversation" style="padding: 0 16px">
      <div class="cx-card" style="margin-top: 4px; padding: 10px 12px">
        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px">
          <i class="mdi" :class="pendingFile.kind === 'directory' ? 'mdi-folder' : 'mdi-file-outline'" />
          <span style="font-weight: 600">{{ pendingFile.name }}</span>
          <span v-if="pendingFile.kind === 'directory'" class="cx-muted" style="font-size: 11px">({{ pendingFile.source === 'native' ? 'native path' : 'upload' }})</span>
        </div>
        <div class="cx-muted" style="font-size: 12px; margin-bottom: 8px">
          {{ $t('aichat.attachPendingHint', { kind: pendingFile.kind }) }}
        </div>
        <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
          <button
            class="cx-btn cx-btn--primary cx-btn--sm"
            :disabled="granting"
            @click="confirmPending"
          ><span v-if="granting" class="cx-spin" /> {{ $t('aichat.approveSend') }}</button>
          <button class="cx-btn cx-btn--text cx-btn--sm" :disabled="granting" @click="cancelPending">{{ $t('aichat.rejectSend') }}</button>
        </div>
      </div>
    </div>

    <!-- Active files for this conversation (committed grants; plugin chosen pre-grant) -->
    <div v-if="ai.activeFiles.length" class="cx-conversation" style="padding: 0 16px">
      <div style="display: flex; flex-wrap: wrap; gap: 8px; align-items: center">
        <span
          v-for="entry in ai.activeFiles"
          :key="entry.ref.id"
          class="cx-chip"
          style="gap: 6px"
        >
          <i class="mdi" :class="entry.ref.kind === 'directory' ? 'mdi-folder' : 'mdi-file-outline'" />
          {{ entry.ref.name }}
          <span class="cx-muted" style="font-size: 11px">[{{ entry.pluginId }}]</span>
          <button class="cx-iconbtn cx-iconbtn--sm" @click="ai.removeActiveFile(entry.pluginId, entry.ref.id)">
            <i class="mdi mdi-close" />
          </button>
        </span>
      </div>
    </div>

    <!-- Composer -->
    <div style="padding: 8px 16px 16px">
      <div v-if="hasError" class="cx-alert cx-alert--error cx-conversation" style="margin-bottom: 8px">
        <span class="cx-alert__body">{{ ai.error }}</span>
        <button class="cx-iconbtn cx-iconbtn--sm" @click="ai.error = null"><i class="mdi mdi-close" /></button>
      </div>

      <div class="cx-composer" style="display: block; padding: 0; position: relative">
        <div v-for="item in composerConfirmations" :key="item.confirmationId" style="padding: 12px 14px; border-bottom: 1px solid var(--cx-border)">
          <div style="display: flex; align-items: center; gap: 8px; font-weight: 650; margin-bottom: 7px">
            <i class="mdi mdi-shield-outline" />{{ $t('aichat.confirmTitle') }}
          </div>
          <div v-for="row in item.summary" :key="row.label" style="font-size: 12px; display: flex; gap: 8px; margin: 3px 0">
            <span class="cx-muted" style="min-width: 74px">{{ row.label }}</span>
            <code style="overflow-wrap: anywhere">{{ row.value }}</code>
          </div>
          <div v-if="item.status === 'pending'" style="display: flex; gap: 8px; margin-top: 10px">
            <button class="cx-btn cx-btn--primary cx-btn--sm" @click="ai.resolveConfirmation(item, true)">{{ $t('aichat.approveOnce') }}</button>
            <button class="cx-btn cx-btn--text cx-btn--sm" @click="ai.resolveConfirmation(item, false)">{{ $t('aichat.rejectSend') }}</button>
          </div>
          <div v-else-if="item.status === 'submitting'" class="cx-muted"><span class="cx-spin" /> {{ $t('aichat.submittingApproval') }}</div>
          <div v-else class="cx-alert cx-alert--error">{{ item.error }}</div>
        </div>
        <div style="padding: 10px 14px 2px">
          <textarea
            ref="textarea"
            v-model="draft"
            rows="1"
            class="cx-grow"
            style="width: 100%; padding: 6px 0"
            :placeholder="$t('aichat.placeholder')"
            @input="autosize"
            @keydown="onKeydown"
            @compositionstart="composing = true"
            @compositionend="onCompositionEnd"
          />
        </div>

        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 3px 10px 9px">
          <div style="display: flex; align-items: center; gap: 4px; min-width: 0">
            <button
              class="cx-iconbtn cx-iconbtn--round"
              :disabled="ai.busy || !!pendingFile"
              :title="$t('aichat.addContext')"
              @click="attachMenuOpen = !attachMenuOpen"
            ><i class="mdi mdi-plus" /></button>
            <div v-if="attachMenuOpen" class="cx-card" style="position: absolute; left: 8px; bottom: 48px; min-width: 210px; padding: 7px; z-index: 22; box-shadow: 0 12px 32px rgba(0,0,0,.18)">
              <button class="cx-btn cx-btn--text" style="width: 100%; justify-content: flex-start" @click="attachFile">
                <i class="mdi mdi-file-outline" />{{ $t('aichat.attachFile') }}
              </button>
              <button class="cx-btn cx-btn--text" style="width: 100%; justify-content: flex-start" @click="attachDirectory">
                <i class="mdi mdi-folder-outline" />{{ $t('aichat.attachDirectory') }}
              </button>
            </div>

            <button class="cx-btn cx-btn--text cx-btn--sm" :style="ai.permissionMode === 'full-access' ? 'color: rgb(var(--v-theme-error))' : ''" style="padding: 3px 6px" :disabled="ai.busy" @click="permissionMenuOpen = !permissionMenuOpen">
              <i class="mdi" :class="ai.permissionMode === 'full-access' ? 'mdi-shield-alert-outline' : 'mdi-shield-check-outline'" />
              {{ ai.permissionMode === 'ask-for-approval' ? $t('aichat.permissionAsk') : ai.permissionMode === 'approve-for-me' ? $t('aichat.permissionAuto') : $t('aichat.permissionFullAccess') }}
              <i class="mdi mdi-chevron-down" />
            </button>
            <div v-if="permissionMenuOpen && !ai.busy" class="cx-card" style="position: absolute; left: 44px; bottom: 48px; width: min(440px, calc(100% - 52px)); padding: 8px; z-index: 21; box-shadow: 0 12px 32px rgba(0,0,0,.18)">
              <div class="cx-muted" style="padding: 5px 10px 8px; font-size: 12px">{{ $t('aichat.permissionQuestion') }}</div>
              <button v-for="option in permissionOptions" :key="option.id" class="cx-btn cx-btn--text" style="width: 100%; height: auto; justify-content: flex-start; text-align: left; padding: 10px; gap: 12px" :style="option.id === 'full-access' ? 'color: rgb(var(--v-theme-error))' : ''" @click="selectPermissionMode(option.id)">
                <i class="mdi" :class="option.icon" style="font-size: 20px" />
                <span style="display: grid; gap: 2px; flex: 1">
                  <span style="font-weight: 650">{{ option.title }}</span>
                  <span class="cx-muted" style="font-size: 12px; white-space: normal">{{ option.description }}</span>
                </span>
                <i v-if="ai.permissionMode === option.id" class="mdi mdi-check" />
              </button>
            </div>
          </div>

          <div style="display: flex; align-items: center; gap: 4px; min-width: 0">
            <button
              class="cx-btn cx-btn--text cx-btn--sm"
              style="padding: 3px 6px; max-width: min(320px, 42vw)"
              :disabled="modelSwitching || modelOptions.length === 0 || ai.busy"
              :title="$t('aichat.chooseModel')"
              @click="modelMenuOpen = !modelMenuOpen"
            >
              <i class="mdi mdi-lightning-bolt" />
              <span style="overflow: hidden; text-overflow: ellipsis">{{ activeModel?.model ?? (modelOptions.length ? $t('aichat.selectModelShort') : $t('aichat.noConfiguredModelsShort')) }}</span>
              <span v-if="activeModel" class="cx-muted">{{ activeModel.provider }}</span>
              <i v-if="modelSwitching" class="mdi mdi-loading mdi-spin" />
              <i v-else class="mdi mdi-chevron-down" />
            </button>
            <div v-if="modelMenuOpen" class="cx-card" style="position: absolute; right: 52px; bottom: 48px; width: min(380px, calc(100% - 16px)); padding: 7px; z-index: 22; box-shadow: 0 12px 32px rgba(0,0,0,.18)">
              <div class="cx-muted" style="padding: 5px 10px 8px; font-size: 12px">{{ $t('aichat.configuredModels') }}</div>
              <button v-for="option in modelOptions" :key="option.mode" class="cx-btn cx-btn--text" style="width: 100%; height: auto; justify-content: flex-start; padding: 9px 10px; gap: 10px" @click="selectModel(option.mode)">
                <i class="mdi mdi-lightning-bolt" />
                <span style="display: grid; flex: 1; text-align: left">
                  <span style="font-weight: 650">{{ option.model }}</span>
                  <span class="cx-muted" style="font-size: 12px">{{ option.provider }}</span>
                </span>
                <i v-if="settings.aiSettings?.activeMode === option.mode" class="mdi mdi-check" />
              </button>
            </div>

            <button class="cx-iconbtn cx-iconbtn--round" :class="{ 'cx-iconbtn--primary': listening }" :title="$t('aichat.voiceInput')" @click="toggleVoiceInput">
              <i class="mdi" :class="listening ? 'mdi-microphone' : 'mdi-microphone-outline'" />
            </button>
            <button
              v-if="ai.busy"
              class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
              :title="$t('aichat.stop')"
              @click="ai.stop()"
            ><i class="mdi mdi-stop" /></button>
            <button
              v-else
              class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
              :disabled="!draft.trim() || modelSwitching || !activeModel"
              :title="$t('aichat.send')"
              @click="submit"
            ><i class="mdi mdi-arrow-up" /></button>
          </div>
        </div>
      </div>
      <div class="cx-conversation cx-muted" style="text-align: center; font-size: 12px; margin-top: 8px">
        {{ $t('aichat.hint') }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-empty-avatar {
  width: 46px;
  height: 46px;
  margin-bottom: 16px;
  padding: 7px;
  background: #0d0d0d;
  border-radius: 11px;
}
.ai-empty-avatar img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
</style>
