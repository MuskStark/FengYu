<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore, type ChatTurn, type Conversation } from '@/stores/aiSession'
import { useSettingsStore } from '@/stores/settings'
import { makeDesktop, isDesktop, canRevealArtifacts, openArtifact, revealArtifact, confirmAction } from '@/mf/desktop'
import { diffLines } from '@/stores/aiToolActivity'
import { api } from '@/api/client'
import type { AiMode, ChatArtifact, ChatResource } from '@/api/types'
import { renderMarkdown } from '@/security/markdown'
import { composerSubmissionText } from './aiChatComposer'
import { configuredChatModels } from './aiChatModels'

const { t, locale } = useI18n()
const ai = useAiSessionStore()
const settings = useSettingsStore()
const scroller = ref<HTMLElement | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)
const permissionMenuOpen = ref(false)
const attachMenuOpen = ref(false)
const modelMenuOpen = ref(false)
const modelSwitching = ref(false)
const listening = ref(false)
const copiedId = ref<number | null>(null)
const resourcesExpanded = ref(false)
/** Browser-only workspace attach dialog (desktop uses the native directory picker). */
const workspaceDialog = ref(false)
const workspacePathInput = ref('')
/** Activity ids whose unified diff is expanded in the timeline. */
const expandedActivityDiffs = ref<Set<string>>(new Set())
/** Artifact ids with a save/download action in flight (per-card spinner). */
const busyArtifactIds = ref<Set<string>>(new Set())
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
  document.addEventListener('pointerdown', closeMenusOnOutsideClick)
})

onBeforeUnmount(() => {
  speechRecognition?.stop()
  document.removeEventListener('pointerdown', closeMenusOnOutsideClick)
})

/**
 * The composer's three popover menus (attach / permission / model) close on any
 * pointerdown outside their own trigger+menu pair. Each pair is tagged with the same
 * `data-menu` name (mirrors the sidebar account menu's outside-click handling); a
 * click inside the pair is left alone so the trigger's own toggle still works.
 */
function closeMenusOnOutsideClick(event: PointerEvent) {
  const within = (event.target as HTMLElement).closest('[data-menu]')?.getAttribute('data-menu')
  if (within !== 'attach') attachMenuOpen.value = false
  if (within !== 'permission') permissionMenuOpen.value = false
  if (within !== 'model') modelMenuOpen.value = false
}

/** The broom deletes the whole conversation — irreversible, so it confirms like the sidebar X. */
async function clearConversation() {
  if (!await confirmAction(t('aichat.clearConfirm'))) return
  await ai.clear()
}

// The draft belongs to the active conversation: switching preserves each conversation's
// half-written text instead of dragging one shared draft everywhere (task doc 4.3).
const draft = computed<string>({
  get: () => ai.active?.draft ?? '',
  set: (value) => {
    const conv = ai.active
    if (conv) conv.draft = value
  },
})

// Memoized markdown: streaming re-runs md() for every turn on each token delta, and the
// marked + DOMPurify pipeline is far too costly to repeat for unchanged content. Insertion-
// order LRU keyed by the source string (identical content dedupes across turns), capped so
// long conversations don't accumulate every intermediate streaming snapshot.
const MD_CACHE_LIMIT = 64
const mdCache = new Map<string, string>()

function md(src: string): string {
  const cached = mdCache.get(src)
  if (cached !== undefined) {
    mdCache.delete(src)
    mdCache.set(src, cached)
    return cached
  }
  const html = renderMarkdown(src)
  mdCache.set(src, html)
  if (mdCache.size > MD_CACHE_LIMIT) {
    const oldest = mdCache.keys().next().value
    if (oldest !== undefined) mdCache.delete(oldest)
  }
  return html
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
  const conv = ai.active
  if (conv) conv.draft = ''
  void nextTick(autosize)
  void ai.send(text)
}

// ── attachments: the conversation that opened the picker owns the result ─────────────

function attachFailure(kind: 'file' | 'directory' | 'output' | 'workspace', error: unknown) {
  const fallback = kind === 'output'
    ? t('aichat.outputTargetFailed')
    : kind === 'workspace' ? t('aichat.workspaceSetFailed')
      : kind === 'directory' ? t('aichat.attachDirectoryFailed') : t('aichat.attachFileFailed')
  ai.error = error instanceof Error && error.message ? error.message : fallback
}

/**
 * Adding a file only writes a DRAFT attachment into the conversation that opened the picker —
 * no backend call, no grant, no copy until send (E01/E02). No approval card, no plugin pick.
 */
async function attachFile() {
  attachMenuOpen.value = false
  const conv = ai.ensureConversation()
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickFile()
    if (!path) return
    // A switch or new chat during the picker never redirects the draft: only a live
    // conversation with this id receives it; deleting the origin simply drops it.
    if (ai.conversations.some(c => c.id === conv.id)) ai.attachNative(conv, path, 'file')
    return
  }
  const input = document.createElement('input')
  input.type = 'file'
  input.onchange = () => {
    const file = input.files?.[0]
    if (!file || !ai.conversations.some(c => c.id === conv.id)) return
    ai.attachUpload(conv, file)
  }
  input.click()
}

/** Adding a folder works the same way: a draft attachment, read-only, subdirectories included. */
async function attachDirectory() {
  attachMenuOpen.value = false
  const conv = ai.ensureConversation()
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickDirectory()
    if (!path) return
    if (ai.conversations.some(c => c.id === conv.id)) ai.attachNative(conv, path, 'directory')
    return
  }
  const input = document.createElement('input')
  input.type = 'file'
  input.setAttribute('webkitdirectory', '')
  input.multiple = true
  input.onchange = () => {
    const files = input.files ? Array.from(input.files) : []
    if (files.length === 0 || !ai.conversations.some(c => c.id === conv.id)) return
    ai.attachUploadDirectory(conv, files)
  }
  input.click()
}

/**
 * The output location authorizes the HOST to save generated results there — it never widens
 * any worker's writable roots. Desktop shells only: a browser cannot host-save to the local
 * disk, so the entry stays hidden there (the web keeps download-based saving).
 */
async function chooseOutputLocation() {
  attachMenuOpen.value = false
  const conv = ai.ensureConversation()
  const desktop = makeDesktop()
  if (!desktop) return
  const path = await desktop.pickDirectory()
  if (!path) return
  try {
    await ai.setOutputTarget(conv, path)
  } catch (e) {
    attachFailure('output', e)
  }
}

async function clearOutputLocation() {
  const conv = ai.active
  if (!conv) return
  try {
    await ai.setOutputTarget(conv, null)
  } catch (e) {
    attachFailure('output', e)
  }
}

/**
 * The coding workspace is the folder the model's read/write/edit/grep/glob tools operate in.
 * Desktop picks it natively; the browser form asks for the absolute path (the backend runs on
 * this machine, so a typed local path is meaningful there too).
 */
async function chooseWorkspace() {
  attachMenuOpen.value = false
  const conv = ai.ensureConversation()
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickDirectory()
    if (!path) return
    try {
      await ai.setWorkspace(conv, path)
    } catch (e) {
      attachFailure('workspace', e)
    }
    return
  }
  workspacePathInput.value = conv.workspaceRoot ?? ''
  workspaceDialog.value = true
}

async function confirmWorkspacePath() {
  const path = workspacePathInput.value.trim()
  if (!path) return
  workspaceDialog.value = false
  const conv = ai.active
  if (!conv) return
  try {
    await ai.setWorkspace(conv, path)
  } catch (e) {
    attachFailure('workspace', e)
  }
}

async function clearWorkspace() {
  const conv = ai.active
  if (!conv) return
  try {
    await ai.setWorkspace(conv, null)
  } catch (e) {
    attachFailure('workspace', e)
  }
}

function removeResource(resource: ChatResource) {
  const conv = ai.active
  if (conv) ai.removeResource(conv, resource.resourceId)
}

/** Removing a draft attachment is purely local — nothing was ever sent or granted (E02). */
function removeDraftAttachment(attachment: { attachmentId: string }) {
  const conv = ai.active
  if (conv) ai.removeDraftAttachment(conv, attachment.attachmentId)
}

async function refreshResource(resource: ChatResource) {
  const conv = ai.active
  if (!conv) return
  try {
    await ai.refreshResource(conv, resource.resourceId)
  } catch (e) {
    ai.error = e instanceof Error && e.message ? e.message : t('aichat.refreshFailed')
  }
}

function folderBasename(path: string): string {
  return path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? path
}

function toggleActivityDiff(id: string) {
  const next = new Set(expandedActivityDiffs.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedActivityDiffs.value = next
}

/** Same-name files from different folders stay distinct chips; the parent disambiguates. */
function resourceSubtitle(resource: ChatResource): string {
  if (resource.kind === 'directory') return t('aichat.resourceFolder')
  return resource.source === 'native' && resource.displayPath
    ? (resource.displayPath.split(/[\\/]/).slice(-2, -1)[0] ?? '')
    : ''
}

// ── artifacts: the host-side save closure ────────────────────────────────────────────

function withBusyArtifact(artifactId: string, busy: boolean) {
  if (busy) busyArtifactIds.value.add(artifactId)
  else busyArtifactIds.value.delete(artifactId)
}

/** Save into the conversation's registered output target (also the retry path). */
async function saveArtifactToTarget(turn: ChatTurn, artifact: ChatArtifact) {
  const conv = ai.active
  if (!conv?.outputTarget) return
  withBusyArtifact(artifact.artifactId, true)
  try {
    await ai.saveArtifact(conv, turn, artifact.artifactId, conv.outputTarget)
  } catch (e) {
    ai.error = e instanceof Error && e.message ? e.message : t('aichat.artifactSaveFailed')
  } finally {
    withBusyArtifact(artifact.artifactId, false)
  }
}

/** Pick a location with a user gesture, register it as the target, then save (7.1). */
async function saveArtifactToChosenLocation(turn: ChatTurn, artifact: ChatArtifact) {
  const conv = ai.active
  if (!conv) return
  const desktop = makeDesktop()
  if (!desktop) return
  const path = await desktop.pickDirectory()
  if (!path) return // cancelling the picker never destroys the result
  withBusyArtifact(artifact.artifactId, true)
  try {
    await ai.setOutputTarget(conv, path)
    await ai.saveArtifact(conv, turn, artifact.artifactId, path)
  } catch (e) {
    ai.error = e instanceof Error && e.message ? e.message : t('aichat.artifactSaveFailed')
  } finally {
    withBusyArtifact(artifact.artifactId, false)
  }
}

/** The browser's save path: download the retained pending copy. */
async function downloadArtifact(artifact: ChatArtifact) {
  withBusyArtifact(artifact.artifactId, true)
  try {
    await api.downloadChatArtifact(artifact.artifactId)
  } catch (e) {
    ai.error = e instanceof Error && e.message ? e.message : t('aichat.artifactSaveFailed')
  } finally {
    withBusyArtifact(artifact.artifactId, false)
  }
}

async function openSavedArtifact(artifact: ChatArtifact) {
  try {
    await openArtifact(artifact.artifactId)
  } catch (e) {
    ai.error = e instanceof Error && e.message ? e.message : t('aichat.artifactOpenFailed')
  }
}

async function revealSavedArtifact(artifact: ChatArtifact) {
  try {
    await revealArtifact(artifact.artifactId)
  } catch (e) {
    ai.error = e instanceof Error && e.message ? e.message : t('aichat.artifactOpenFailed')
  }
}

const artifactActionsAvailable = computed(() => canRevealArtifacts())
const revealLabel = computed(() =>
  window.fengyu?.platform === 'darwin' ? t('aichat.revealInFinder') : t('aichat.revealInFolder'))

function artifactStateLabel(artifact: ChatArtifact): string {
  switch (artifact.state) {
    case 'saved': return t('aichat.artifactSaved')
    case 'saving': return t('aichat.artifactSaving')
    case 'save-failed': return t('aichat.artifactSaveFailedShort')
    default: return t('aichat.artifactPending')
  }
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
const activeConv = computed<Conversation | null>(() => ai.active)
const showExpandedResources = computed(() =>
  ai.turns.length === 0 || resourcesExpanded.value)
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
  resourcesExpanded.value = false
  await nextTick()
  autosize()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
})
</script>

<template>
  <div class="d-flex flex-column h-100" style="display: flex; flex-direction: column; height: 100%; position: relative">
    <!-- Top bar -->
    <div class="cx-topbar" style="border-bottom: none; min-height: 48px">
      <span
        v-if="!empty && activeConv?.title"
        class="cx-muted"
        style="font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: inline-flex; align-items: center; gap: 7px"
      >
        <i class="mdi mdi-chevron-right" style="opacity: .5" />{{ activeConv.title }}
      </span>
      <div style="flex: 1 1 auto"></div>
      <button v-if="!empty" class="cx-btn cx-btn--text cx-btn--sm" @click="clearConversation">
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
          <div v-if="turn.role === 'user'">
            <div class="cx-msg-body">{{ turn.content }}</div>
            <div v-if="turn.attachments.length" style="display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px">
              <span v-for="(item, index) in turn.attachments" :key="index" class="cx-chip" style="gap: 5px; font-size: 12px">
                <i class="mdi" :class="item.kind === 'directory' ? 'mdi-folder-outline' : 'mdi-file-outline'" />
                {{ item.name }}
              </span>
            </div>
          </div>
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
              <div v-for="activity in turn.activities" :key="activity.id" style="display: grid; gap: 4px">
                <div class="cx-muted" style="display: flex; gap: 8px; align-items: center; font-size: 13px">
                  <i class="mdi" :class="activityIcon(activity.status)" />
                  <span style="color: rgb(var(--v-theme-on-surface))">{{ activity.label }}</span>
                  <span v-if="activity.status === 'waiting'">{{ $t('aichat.awaitingApproval') }}</span>
                  <span v-else-if="activity.status === 'failed'">{{ $t('aichat.toolFailed') }}</span>
                  <button
                    v-if="activity.diff"
                    class="cx-btn cx-btn--text cx-btn--sm"
                    style="margin-left: auto; padding: 0 6px"
                    @click="toggleActivityDiff(activity.id)"
                  >{{ expandedActivityDiffs.has(activity.id) ? $t('aichat.diffHide') : $t('aichat.diffShow') }}</button>
                </div>
                <div v-if="activity.diff && expandedActivityDiffs.has(activity.id)" class="cx-diff">
                  <div v-for="(line, index) in diffLines(activity.diff)" :key="index" :class="`cx-diff__${line.kind}`">{{ line.text }}</div>
                </div>
              </div>
            </div>

            <!-- Approval prompts live in the composer; the transcript keeps only compact activity rows. -->
            <div class="cx-md" v-html="md(turn.content)" />

            <!-- Generated results: one card per artifact with its save state -->
            <div v-if="turn.artifacts.length" style="display: grid; gap: 8px; margin: 10px 0 4px">
              <div
                v-for="artifact in turn.artifacts"
                :key="artifact.artifactId"
                class="cx-card"
                style="padding: 10px 12px"
              >
                <div style="display: flex; align-items: center; gap: 8px; min-width: 0">
                  <i class="mdi mdi-file-check-outline" />
                  <span style="font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ artifact.name }}</span>
                  <span
                    class="cx-muted"
                    :style="artifact.state === 'save-failed' ? 'color: rgb(var(--v-theme-error)); font-size: 12px' : 'font-size: 12px'"
                  >{{ artifactStateLabel(artifact) }}</span>
                </div>
                <div v-if="artifact.savedPath" class="cx-muted" style="font-size: 12px; margin: 4px 0 6px; overflow-wrap: anywhere">
                  {{ artifact.savedPath }}
                </div>
                <div v-else-if="artifact.state === 'save-failed' && artifact.error" class="cx-muted" style="font-size: 12px; margin: 4px 0 6px; overflow-wrap: anywhere">
                  {{ artifact.error }}
                </div>
                <div style="display: flex; gap: 8px; flex-wrap: wrap">
                  <template v-if="artifact.state === 'saved'">
                    <button v-if="artifactActionsAvailable" class="cx-btn cx-btn--text cx-btn--sm" @click="openSavedArtifact(artifact)">
                      <i class="mdi mdi-open-in-new" />{{ $t('aichat.openArtifact') }}
                    </button>
                    <button v-if="artifactActionsAvailable" class="cx-btn cx-btn--text cx-btn--sm" @click="revealSavedArtifact(artifact)">
                      <i class="mdi mdi-folder-open-outline" />{{ revealLabel }}
                    </button>
                  </template>
                  <template v-else-if="artifact.state === 'ready-to-save' || artifact.state === 'save-failed'">
                    <button
                      v-if="activeConv?.outputTarget"
                      class="cx-btn cx-btn--primary cx-btn--sm"
                      :disabled="busyArtifactIds.has(artifact.artifactId)"
                      @click="saveArtifactToTarget(turn, artifact)"
                    >{{ artifact.state === 'save-failed' ? $t('aichat.retrySave') : $t('aichat.saveToTarget') }}</button>
                    <button
                      v-if="isDesktop()"
                      class="cx-btn cx-btn--text cx-btn--sm"
                      :disabled="busyArtifactIds.has(artifact.artifactId)"
                      @click="saveArtifactToChosenLocation(turn, artifact)"
                    >{{ $t('aichat.chooseSaveLocation') }}</button>
                    <button
                      v-if="!isDesktop()"
                      class="cx-btn cx-btn--text cx-btn--sm"
                      :disabled="busyArtifactIds.has(artifact.artifactId)"
                      @click="downloadArtifact(artifact)"
                    >{{ $t('aichat.downloadArtifact') }}</button>
                  </template>
                </div>
              </div>
            </div>

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

    <!-- Conversation attachments: unsent drafts first, then committed resources — one chip per
         aggregated selection (never per plugin) -->
    <div v-if="ai.activeDraftAttachments.length || ai.activeResources.length || activeConv?.outputTarget || activeConv?.workspaceRoot || (activeConv?.attaching ?? 0) > 0" class="cx-conversation" style="padding: 0 16px">
      <div style="display: flex; flex-wrap: wrap; gap: 8px; align-items: center">
        <span
          v-for="attachment in ai.activeDraftAttachments"
          :key="attachment.attachmentId"
          class="cx-chip"
          style="gap: 6px"
          :title="attachment.displayPath ?? attachment.name"
        >
          <i class="mdi" :class="attachment.kind === 'directory' ? 'mdi-folder' : 'mdi-file-outline'" />
          {{ attachment.name }}
          <span class="cx-muted" style="font-size: 11px">{{ $t('aichat.attachmentReadOnlyHint') }}</span>
          <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.removeResource')" @click="removeDraftAttachment(attachment)">
            <i class="mdi mdi-close" />
          </button>
        </span>
        <button
          v-if="ai.turns.length > 0 && !resourcesExpanded && ai.activeResources.length"
          class="cx-chip"
          style="gap: 6px; cursor: pointer"
          @click="resourcesExpanded = true"
        >
          <i class="mdi mdi-paperclip" />
          {{ $t('aichat.resourceCount', { count: ai.activeResources.length }) }}
          <i class="mdi mdi-chevron-down" />
        </button>
        <template v-if="showExpandedResources">
          <span
            v-for="resource in ai.activeResources"
            :key="resource.resourceId"
            class="cx-chip"
            style="gap: 6px"
            :title="resource.displayPath ?? resource.name"
          >
            <i class="mdi" :class="resource.kind === 'directory' ? 'mdi-folder' : 'mdi-file-outline'" />
            {{ resource.name }}
            <span class="cx-muted" style="font-size: 11px">
              {{ resourceSubtitle(resource) ? `${resourceSubtitle(resource)} · ` : '' }}{{ $t('aichat.resourceReadOnly') }}
            </span>
            <button
              v-if="resource.kind === 'file' && resource.source === 'native'"
              class="cx-iconbtn cx-iconbtn--sm"
              :title="$t('aichat.refreshResource')"
              @click="refreshResource(resource)"
            ><i class="mdi mdi-refresh" /></button>
            <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.removeResource')" @click="removeResource(resource)">
              <i class="mdi mdi-close" />
            </button>
          </span>
        </template>
        <span v-if="(activeConv?.attaching ?? 0) > 0" class="cx-chip" style="gap: 6px">
          <span class="cx-spin" />{{ $t('aichat.attachInProgress') }}
        </span>
        <span v-if="activeConv?.outputTarget" class="cx-chip" style="gap: 6px" :title="activeConv.outputTarget">
          <i class="mdi mdi-content-save-outline" />
          {{ $t('aichat.saveToPrefix') }}{{ folderBasename(activeConv.outputTarget) }}
          <button
            v-if="isDesktop()"
            class="cx-iconbtn cx-iconbtn--sm"
            :title="$t('aichat.changeOutputFolder')"
            @click="chooseOutputLocation"
          ><i class="mdi mdi-pencil-outline" /></button>
          <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.clearOutputFolder')" @click="clearOutputLocation">
            <i class="mdi mdi-close" />
          </button>
        </span>
        <span v-if="activeConv?.workspaceRoot" class="cx-chip" style="gap: 6px" :title="activeConv.workspaceRoot">
          <i class="mdi mdi-code-braces" />
          {{ $t('aichat.workspacePrefix') }}{{ folderBasename(activeConv.workspaceRoot) }}
          <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.changeWorkspace')" @click="chooseWorkspace">
            <i class="mdi mdi-pencil-outline" />
          </button>
          <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.clearWorkspace')" @click="clearWorkspace">
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
              :title="$t('aichat.addContext')"
              data-menu="attach"
              @click="attachMenuOpen = !attachMenuOpen"
            ><i class="mdi mdi-plus" /></button>
            <div v-if="attachMenuOpen" data-menu="attach" class="cx-card" style="position: absolute; left: 8px; bottom: 48px; min-width: 230px; padding: 7px; z-index: 22; box-shadow: 0 12px 32px rgba(0,0,0,.18)">
              <div class="cx-muted" style="padding: 5px 10px 6px; font-size: 11px">{{ $t('aichat.attachMenuHint') }}</div>
              <button class="cx-btn cx-btn--text" style="width: 100%; justify-content: flex-start" @click="attachFile">
                <i class="mdi mdi-file-outline" />
                <span style="display: grid; text-align: left">
                  <span>{{ $t('aichat.attachFile') }}</span>
                  <span class="cx-muted" style="font-size: 11px">{{ $t('aichat.attachFileHint') }}</span>
                </span>
              </button>
              <button class="cx-btn cx-btn--text" style="width: 100%; justify-content: flex-start" @click="attachDirectory">
                <i class="mdi mdi-folder-outline" />
                <span style="display: grid; text-align: left">
                  <span>{{ $t('aichat.attachDirectory') }}</span>
                  <span class="cx-muted" style="font-size: 11px">{{ $t('aichat.attachDirectoryHint') }}</span>
                </span>
              </button>
              <button class="cx-btn cx-btn--text" style="width: 100%; justify-content: flex-start" @click="chooseWorkspace">
                <i class="mdi mdi-code-braces" />
                <span style="display: grid; text-align: left">
                  <span>{{ $t('aichat.setWorkspace') }}</span>
                  <span class="cx-muted" style="font-size: 11px">{{ $t('aichat.setWorkspaceHint') }}</span>
                </span>
              </button>
              <button v-if="isDesktop()" class="cx-btn cx-btn--text" style="width: 100%; justify-content: flex-start" @click="chooseOutputLocation">
                <i class="mdi mdi-content-save-outline" />
                <span style="display: grid; text-align: left">
                  <span>{{ $t('aichat.setOutputFolder') }}</span>
                  <span class="cx-muted" style="font-size: 11px">{{ $t('aichat.setOutputFolderHint') }}</span>
                </span>
              </button>
            </div>

            <!-- Browser workspace attach: the backend runs on this machine, so a typed local
                 absolute path is the folder the coding tools will operate in. -->
            <div v-if="workspaceDialog" class="cx-card" style="position: absolute; inset: auto 8px 44px 8px; padding: 12px; z-index: 22; box-shadow: 0 12px 32px rgba(0,0,0,.18)">
              <div style="font-weight: 650; margin-bottom: 6px">{{ $t('aichat.workspacePathPrompt') }}</div>
              <input
                v-model="workspacePathInput"
                class="cx-grow"
                style="width: 100%; padding: 6px 2px; font-size: 13px"
                :placeholder="$t('aichat.workspacePathPlaceholder')"
                @keydown.enter="confirmWorkspacePath"
              >
              <div style="display: flex; gap: 8px; justify-content: flex-end; margin-top: 10px">
                <button class="cx-btn cx-btn--text cx-btn--sm" @click="workspaceDialog = false">{{ $t('common.cancel') }}</button>
                <button class="cx-btn cx-btn--primary cx-btn--sm" :disabled="!workspacePathInput.trim()" @click="confirmWorkspacePath">
                  {{ $t('aichat.workspaceAttach') }}
                </button>
              </div>
            </div>

            <button data-menu="permission" class="cx-btn cx-btn--text cx-btn--sm" :style="ai.permissionMode === 'full-access' ? 'color: rgb(var(--v-theme-error))' : ''" style="padding: 3px 6px" :disabled="ai.busy" @click="permissionMenuOpen = !permissionMenuOpen">
              <i class="mdi" :class="ai.permissionMode === 'full-access' ? 'mdi-shield-alert-outline' : 'mdi-shield-check-outline'" />
              {{ ai.permissionMode === 'ask-for-approval' ? $t('aichat.permissionAsk') : ai.permissionMode === 'approve-for-me' ? $t('aichat.permissionAuto') : $t('aichat.permissionFullAccess') }}
              <i class="mdi mdi-chevron-down" />
            </button>
            <div v-if="permissionMenuOpen && !ai.busy" data-menu="permission" class="cx-card" style="position: absolute; left: 44px; bottom: 48px; width: min(440px, calc(100% - 52px)); padding: 8px; z-index: 21; box-shadow: 0 12px 32px rgba(0,0,0,.18)">
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
              data-menu="model"
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
            <div v-if="modelMenuOpen" data-menu="model" class="cx-card" style="position: absolute; right: 52px; bottom: 48px; width: min(380px, calc(100% - 16px)); padding: 7px; z-index: 22; box-shadow: 0 12px 32px rgba(0,0,0,.18)">
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
.cx-diff {
  max-height: 320px;
  overflow: auto;
  padding: 8px 10px;
  border: 1px solid var(--cx-border);
  border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre;
}
.cx-diff__add { color: rgb(var(--v-theme-success)); }
.cx-diff__del { color: rgb(var(--v-theme-error)); }
.cx-diff__hunk { color: rgb(var(--v-theme-primary)); opacity: 0.85; }
.cx-diff__ctx { color: rgba(var(--v-theme-on-surface), 0.72); }
</style>
