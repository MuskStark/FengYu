<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore } from '@/stores/aiSession'
import { confirmAction } from '@/mf/desktop'
import ChatTranscript from '@/components/chat/ChatTranscript.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import WorkspacePanel from '@/components/chat/WorkspacePanel.vue'

/**
 * AI chat view: layout shell, top bar, and the workspace side panel. The scroll region (turn
 * timeline, artifacts, expandable diffs) lives in ChatTranscript; everything the user types or
 * attaches into the next message lives in ChatComposer; the read-only workspace browser
 * (file tree + preview) lives in WorkspacePanel. All three are store-driven.
 */
const { t } = useI18n()
const ai = useAiSessionStore()
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)

const empty = computed(() => ai.turns.length === 0)
const activeConv = computed(() => ai.active)

/** Workspace browsing needs both a root and a persisted conversation id to query. */
const workspaceBinding = computed(() => {
  const conv = ai.active
  return conv?.workspaceRoot && conv.backendId != null
    ? { conversationId: conv.backendId, root: conv.workspaceRoot }
    : null
})

const workspacePanelOpen = ref(false)
/** Pending open-file request for the panel; seq re-fires repeated requests for one path. */
const workspaceFocus = ref<{ path: string; seq: number } | null>(null)
let workspaceFocusSeq = 0

watch(() => workspaceBinding.value, () => {
  // Detaching (or switching to a conversation without) a workspace closes the panel.
  if (!workspaceBinding.value) workspacePanelOpen.value = false
})

function openWorkspaceFile(path: string) {
  workspaceFocus.value = { path, seq: ++workspaceFocusSeq }
  workspacePanelOpen.value = true
}

/** The broom deletes the whole conversation — irreversible, so it confirms like the sidebar X. */
async function clearConversation() {
  if (!await confirmAction(t('aichat.clearConfirm'))) return
  await ai.clear()
}
</script>

<template>
  <div class="chat-view">
    <!-- Top bar -->
    <div class="cx-topbar chat-topbar">
      <span v-if="!empty && activeConv?.title" class="cx-muted chat-title">
        <i class="mdi mdi-chevron-right chat-title__chevron" />{{ activeConv.title }}
      </span>
      <div class="chat-spacer"></div>
      <button
        v-if="workspaceBinding"
        class="cx-btn cx-btn--text cx-btn--sm chat-topbar__workspace"
        :class="{ 'chat-topbar__workspace--active': workspacePanelOpen }"
        :title="$t('aichat.workspacePanel')"
        @click="workspacePanelOpen = !workspacePanelOpen"
      >
        <i class="mdi mdi-folder-outline" />
      </button>
      <button v-if="!empty" class="cx-btn cx-btn--text cx-btn--sm" @click="clearConversation">
        <i class="mdi mdi-broom" />{{ $t('aichat.clear') }}
      </button>
    </div>

    <div class="chat-main">
      <ChatTranscript
        @attach-workspace="composerRef?.chooseWorkspace()"
        @open-workspace-file="openWorkspaceFile"
      />
      <WorkspacePanel
        v-if="workspacePanelOpen && workspaceBinding"
        :conversation-id="workspaceBinding.conversationId"
        :root="workspaceBinding.root"
        :focus="workspaceFocus"
        @close="workspacePanelOpen = false"
      />
    </div>

    <ChatComposer ref="composerRef" />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  position: relative;
}

.cx-topbar.chat-topbar {
  border-bottom: none;
  min-height: 48px;
}

.chat-title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.chat-title__chevron { opacity: 0.5; }

.chat-spacer { flex: 1 1 auto; }

.chat-topbar__workspace { padding: 3px 6px; }
.cx-btn.chat-topbar__workspace--active { background: var(--cx-hover-strong); }

/* Transcript and the optional workspace panel share the row between bar and composer. */
.chat-main {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
}
</style>
