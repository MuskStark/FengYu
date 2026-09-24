<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore, type ChatTurn, type Conversation } from '@/stores/aiSession'
import { diffLines } from '@/stores/aiToolActivity'
import { isDesktop, makeDesktop, canRevealArtifacts, openArtifact, revealArtifact } from '@/mf/desktop'
import { api } from '@/api/client'
import type { ChatArtifact } from '@/api/types'
import { renderMarkdown } from '@/security/markdown'

/**
 * Read-only half of the AI chat view: the scroll region with the empty hero and the turn
 * timeline (markdown, thinking, tool activities with expandable diffs, artifact cards).
 * All mutations go through the session store; this component owns only view-local state
 * (copy flash, diff expansion, per-artifact busy spinners) and the scroll behavior.
 */
const { t } = useI18n()
const ai = useAiSessionStore()
const emit = defineEmits<{
  /** Hero empty-state request to attach a coding workspace (delegated to the composer). */
  'attach-workspace': []
  /** Open a workspace file in the side panel (the activity row's open-file gesture). */
  'open-workspace-file': [path: string]
}>()
const scroller = ref<HTMLElement | null>(null)
const copiedId = ref<number | null>(null)
/** Activity ids whose unified diff is expanded in the timeline. */
const expandedActivityDiffs = ref<Set<string>>(new Set())
/** Artifact ids with a save/download action in flight (per-card spinner). */
const busyArtifactIds = ref<Set<string>>(new Set())

const empty = computed(() => ai.turns.length === 0)
const activeConv = computed<Conversation | null>(() => ai.active)

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

function activityIcon(status: string): string {
  if (status === 'completed') return 'mdi-check'
  if (status === 'failed' || status === 'rejected') return 'mdi-close'
  if (status === 'waiting') return 'mdi-shield-outline'
  return 'mdi-loading mdi-spin'
}

function toggleActivityDiff(id: string) {
  const next = new Set(expandedActivityDiffs.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedActivityDiffs.value = next
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
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
})
</script>

<template>
  <div ref="scroller" class="chat-scroller" @click="onScrollerClick" @keydown="onScrollerKeydown">
    <!-- Empty / hero -->
    <div v-if="empty" class="cx-conversation chat-hero">
      <span class="cx-avatar ai-empty-avatar" aria-hidden="true">
        <img src="/infinia-logo.svg" alt="" />
      </span>
      <div class="chat-hero__title">{{ $t('aichat.heroTitle') }}</div>
      <div class="cx-muted">{{ $t('aichat.empty') }}</div>
      <button class="cx-btn cx-btn--outline cx-btn--sm chat-hero__workspace" @click="emit('attach-workspace')">
        <i class="mdi mdi-folder-plus-outline" />{{ $t('aichat.setWorkspace') }}
      </button>
    </div>

    <!-- Conversation -->
    <div v-else class="cx-conversation chat-transcript">
      <div
        v-for="turn in ai.turns"
        :key="turn.id"
        class="cx-msg"
        :class="{ 'cx-msg--user': turn.role === 'user' }"
      >
        <!-- User: full-width tinted block (block style) -->
        <div v-if="turn.role === 'user'">
          <div class="cx-msg-body">{{ turn.content }}</div>
          <div v-if="turn.attachments.length" class="chat-attachments">
            <span v-for="(item, index) in turn.attachments" :key="index" class="cx-chip chat-attachment-chip">
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

          <details v-if="turn.thinking" class="cx-details chat-thinking">
            <summary>{{ $t('aichat.thinking') }}</summary>
            <div class="cx-details__body cx-md cx-muted" v-html="md(turn.thinking)" />
          </details>

          <div v-if="turn.activities.length" class="chat-activities">
            <div v-for="activity in turn.activities" :key="activity.id" class="chat-activity">
              <div class="cx-muted chat-activity__line">
                <i class="mdi" :class="activityIcon(activity.status)" />
                <span class="chat-activity__label">{{ activity.label }}</span>
                <span v-if="activity.detail" class="chat-activity__detail">{{ activity.detail }}</span>
                <span v-if="activity.status === 'waiting'">{{ $t('aichat.awaitingApproval') }}</span>
                <span v-else-if="activity.status === 'failed'">{{ $t('aichat.toolFailed') }}</span>
                <span v-if="activity.diff || activity.path" class="chat-activity__actions">
                  <button
                    v-if="activity.diff"
                    class="cx-btn cx-btn--text cx-btn--sm chat-activity__action"
                    @click="toggleActivityDiff(activity.id)"
                  >{{ expandedActivityDiffs.has(activity.id) ? $t('aichat.diffHide') : $t('aichat.diffShow') }}</button>
                  <button
                    v-if="activity.path"
                    class="cx-btn cx-btn--text cx-btn--sm chat-activity__action"
                    @click="emit('open-workspace-file', activity.path)"
                  ><i class="mdi mdi-open-in-new" />{{ $t('aichat.openInWorkspacePanel') }}</button>
                </span>
              </div>
              <div v-if="activity.diff && expandedActivityDiffs.has(activity.id)" class="cx-diff">
                <div v-for="(line, index) in diffLines(activity.diff)" :key="index" :class="`cx-diff__${line.kind}`">{{ line.text }}</div>
              </div>
            </div>
          </div>

          <!-- Approval prompts live in the composer; the transcript keeps only compact activity rows. -->
          <div class="cx-md" v-html="md(turn.content)" />

          <!-- Generated results: one card per artifact with its save state -->
          <div v-if="turn.artifacts.length" class="chat-artifacts">
            <div
              v-for="artifact in turn.artifacts"
              :key="artifact.artifactId"
              class="cx-card chat-artifact"
            >
              <div class="chat-artifact__head">
                <i class="mdi mdi-file-check-outline" />
                <span class="chat-artifact__name">{{ artifact.name }}</span>
                <span
                  class="cx-muted chat-artifact__state"
                  :style="artifact.state === 'save-failed' ? 'color: rgb(var(--v-theme-error))' : undefined"
                >{{ artifactStateLabel(artifact) }}</span>
              </div>
              <div v-if="artifact.savedPath" class="cx-muted chat-artifact__path">
                {{ artifact.savedPath }}
              </div>
              <div v-else-if="artifact.state === 'save-failed' && artifact.error" class="cx-muted chat-artifact__path">
                {{ artifact.error }}
              </div>
              <div class="chat-artifact__actions">
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

          <div v-if="turn.streaming && !turn.content" class="chat-streaming">
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
</template>

<style scoped>
.chat-scroller {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 0 16px;
}

/* Empty / hero */
.chat-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  min-height: 55vh;
}
.chat-hero__title {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 4px;
}
.chat-hero__workspace { margin-top: 18px; }
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

.chat-transcript { padding: 16px 0; }

/* Sent attachments under a user message. */
.chat-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.cx-chip.chat-attachment-chip { gap: 5px; font-size: 12px; }

.cx-details.chat-thinking { margin-bottom: 8px; }

/* Tool activity rows (compact timeline; diffs expand on demand). */
.chat-activities {
  display: grid;
  gap: 5px;
  margin: 6px 0 10px;
}
.chat-activity { display: grid; gap: 4px; }
.chat-activity__line {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}
.chat-activity__label { color: rgb(var(--v-theme-on-surface)); }
.chat-activity__detail {
  flex: 0 1 auto;
  min-width: 0;
  font-size: 12px;
  font-family: 'SF Mono', 'JetBrains Mono', ui-monospace, Menlo, Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-activity__actions {
  display: flex;
  gap: 2px;
  margin-left: auto;
}
.cx-btn--text.chat-activity__action { padding: 0 6px; }

/* Generated-result cards. */
.chat-artifacts {
  display: grid;
  gap: 8px;
  margin: 10px 0 4px;
}
.cx-card.chat-artifact { padding: 10px 12px; }
.chat-artifact__head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.chat-artifact__name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-artifact__state { font-size: 12px; }
.chat-artifact__path {
  font-size: 12px;
  margin: 4px 0 6px;
  overflow-wrap: anywhere;
}
.chat-artifact__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chat-streaming { margin-top: 4px; }

/* Expandable unified diff under a write/edit activity row. */
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
