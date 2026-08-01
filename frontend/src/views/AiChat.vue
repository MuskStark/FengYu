<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore, guessPluginForFile } from '@/stores/aiSession'
import { makeDesktop } from '@/mf/desktop'
import { api } from '@/api/client'
import type { PluginDescriptor, PluginFileRef } from '@/api/types'
import { renderMarkdown } from '@/security/markdown'

const { t } = useI18n()
const ai = useAiSessionStore()
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)

/**
 * A file/dir chosen by the user but NOT yet granted. The grant is plugin-scoped and immutable to a
 * plugin, so the user must pick the target plugin BEFORE we hit the grant endpoint. This avoids the
 * old bug where an empty pluginId (unknown extension) produced POST /api/plugin-runtime//files/native
 * (empty segment) and failed the permission lookup before the entry was even added.
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

/** Candidate plugins for the pending-file picker: enabled and declaring files.read. */
const pluginOptions = computed<PluginDescriptor[]>(() =>
  ai.installedPlugins.filter(
    (p) => p.enabled !== false && (p.permissions ?? []).includes('files.read'),
  ),
)
/** Preselected choice: the guessed plugin if it is among the candidates, else ''. */
const chosenPlugin = ref('')

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

/** Refresh the plugin list (best-effort; failures leave the cached list intact). */
async function ensurePlugins() {
  try {
    await ai.loadInstalledPlugins()
  } catch {
    /* surfaced via ai.error elsewhere if a grant later fails */
  }
}

/** Step 1 of the two-step attach: pick a FILE. Stores it as pending WITHOUT granting. */
async function attachFile() {
  if (ai.busy || pendingFile.value) return
  await ensurePlugins()
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
  await ensurePlugins()
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

/** Hold the chosen file as pending and preselect the guessed plugin (if it is a valid candidate). */
function startPending(entry: PendingAttach) {
  pendingFile.value = entry
  const guess = guessPluginForFile(entry.name)
  chosenPlugin.value =
    guess && pluginOptions.value.some((p) => p.id === guess) ? guess : ''
}

/** Step 2: grant the pending file/dir under the chosen plugin, then commit it to the store. */
async function confirmPending() {
  const entry = pendingFile.value
  const pluginId = chosenPlugin.value
  if (!entry || !pluginId || granting.value) return
  granting.value = true
  try {
    let ref: PluginFileRef
    if (entry.source === 'native') {
      ref = await api.grantRuntimeNativePath(pluginId, entry.path!, entry.kind, 'read')
    } else if (entry.kind === 'directory') {
      ref = await api.uploadRuntimeDirectory(pluginId, entry.files!, 'read')
    } else {
      ref = await api.uploadRuntimeFile(pluginId, entry.files![0])
    }
    ai.addActiveFile(pluginId, ref)
    pendingFile.value = null
    chosenPlugin.value = ''
  } catch (e) {
    ai.error = e instanceof Error ? e.message : 'Failed to attach file'
  } finally {
    granting.value = false
  }
}

function cancelPending() {
  pendingFile.value = null
  chosenPlugin.value = ''
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

    <!-- Pending attach: choose a plugin BEFORE granting (grant is plugin-scoped) -->
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
          <select v-model="chosenPlugin" class="cx-input" style="min-width: 220px">
            <option value="" disabled>{{ $t('aichat.selectPlugin') }}</option>
            <option v-for="p in pluginOptions" :key="p.id" :value="p.id">{{ p.name }} ({{ p.id }})</option>
          </select>
          <button
            class="cx-btn cx-btn--primary cx-btn--sm"
            :disabled="!chosenPlugin || granting"
            @click="confirmPending"
          ><span v-if="granting" class="cx-spin" /> {{ $t('aichat.approveSend') }}</button>
          <button class="cx-btn cx-btn--text cx-btn--sm" :disabled="granting" @click="cancelPending">{{ $t('aichat.rejectSend') }}</button>
        </div>
        <div v-if="pluginOptions.length === 0" class="cx-muted" style="font-size: 12px; margin-top: 6px; color: var(--md-sys-color-error)">
          {{ $t('aichat.fileNeedsPlugin') }}
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
          :disabled="ai.busy || !!pendingFile"
          :title="$t('aichat.attachFile')"
          @click="attachFile"
        ><i class="mdi mdi-paperclip" /></button>
        <button
          class="cx-iconbtn cx-iconbtn--round"
          :disabled="ai.busy || !!pendingFile"
          :title="$t('aichat.attachDirectory')"
          @click="attachDirectory"
        ><i class="mdi mdi-folder-plus-outline" /></button>
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
