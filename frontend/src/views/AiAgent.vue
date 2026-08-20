<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import type { AgentRunConfig, AgentRunSummary, AgentTool, AiPermissionMode } from '@/api/types'
import { useAgentRunStream } from '@/components/agent/agentRunStream'

/**
 * Plan-and-Execute agent UI with AI planning.
 *
 * Flow: goal textarea → POST /api/agent/run → open EventSource on
 * /api/agent/stream?runId=… → parse the backend's named SSE events
 * (plan_token / plan_ready / plan_approval_requested / step_start /
 * step_retry / step_complete / step_approval_requested / complete / error) and update
 * reactive plan/steps/status. The visual flow builder lives at /flows —
 * published flows come back here as `run_workflow_*` tools.
 */
const { t } = useI18n()

const goal = ref('')
const tools = ref<AgentTool[]>([])
let toolRefreshTimer: ReturnType<typeof setInterval> | null = null

const stream = useAgentRunStream({ onSettled: () => void loadRunHistory() })
const {
  runId, status, plan, planTokens, stepResults, stepRetries, summary, errorMsg,
  selectedHistoryId, requirePlanApproval, busy, stepList, resetRunState,
  openStream, approve, cancel, showPersistedRun, resumePersisted,
} = stream

// ── run history ──────────────────────────────────────────────────────────
const runHistory = ref<AgentRunSummary[]>([])

async function loadRunHistory() {
  try {
    runHistory.value = await api.agentRuns()
  } catch {
    // History is auxiliary; a current run must remain usable when it cannot be loaded.
  }
}

// Default AI-planning approval/recovery config.
const config: AgentRunConfig = {
  requirePlanApproval: true,
  requireStepApproval: false,
  replanOnFailure: false,
  maxReplans: 0,
  permissionMode: 'ask-for-approval',
}
const permissionMode = ref<AiPermissionMode>('ask-for-approval')

// ── lifecycle ────────────────────────────────────────────────────────────
// Load the tool list once on mount for the "Available tools" hint.
void refreshTools()
onMounted(() => {
  void loadRunHistory()
  window.addEventListener('focus', refreshTools)
  toolRefreshTimer = setInterval(() => void refreshTools(), 10_000)
})

onBeforeUnmount(() => {
  stream.closeStream()
  window.removeEventListener('focus', refreshTools)
  if (toolRefreshTimer) clearInterval(toolRefreshTimer)
})

async function refreshTools() {
  try {
    const list = await api.agentTools()
    tools.value = (list ?? []).filter((tool) => tool.pluginId !== 'workflow')
  } catch {
    // Keep the last known catalog when the host is temporarily unreachable.
  }
}

// ── actions ──────────────────────────────────────────────────────────────

async function run() {
  const g = goal.value.trim()
  if (busy.value || !g) return
  // Reset for a fresh run.
  errorMsg.value = null
  summary.value = null
  resetRunState()
  status.value = 'planning'

  try {
    const runConfig: AgentRunConfig = { ...config }
    runConfig.permissionMode = permissionMode.value
    requirePlanApproval.value = runConfig.requirePlanApproval
    const response = await api.agentRun({ goal: g, config: runConfig })
    runId.value = response.runId
    selectedHistoryId.value = runId.value
    openStream(runId.value)
    await loadRunHistory()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    status.value = 'error'
  }
}

// ── status → i18n label ─────────────────────────────────────────────────
const statusLabel = computed(() => {
  switch (status.value) {
    case 'planning':
      return t('agent.planning')
    case 'awaiting-plan':
      return t('agent.waitingPlanApproval')
    case 'awaiting-step':
      return t('agent.waitingStepApproval')
    case 'running':
    case 'running-remote':
      return t('agent.running')
    case 'complete':
      return t('agent.completed')
    case 'error':
      return t('agent.failed')
    case 'cancelled':
      return t('agent.cancelled')
    default:
      return ''
  }
})

// Codex chip class per run status / per step status.
const statusChipClass = computed(() => {
  switch (status.value) {
    case 'planning':
    case 'running':
    case 'running-remote':
      return 'cx-chip--primary'
    case 'awaiting-plan':
    case 'awaiting-step':
      return 'cx-chip--warn'
    case 'complete':
      return 'cx-chip--success'
    case 'error':
      return 'cx-chip--error'
    default:
      return ''
  }
})
function stepChipClass(s: string): string {
  if (s === 'running') return 'cx-chip--primary'
  if (s === 'retrying') return 'cx-chip--warn'
  if (s === 'complete') return 'cx-chip--success'
  return ''
}
</script>

<template>
  <div class="agent-page">
    <div class="cx-page">
      <h1 class="cx-page-title">{{ t('agent.title') }}</h1>

      <div class="cx-segment agent-mode" role="tablist">
        <button
          class="active"
          role="tab"
          aria-selected="true"
          :disabled="busy"
        ><i class="mdi mdi-auto-fix" /> {{ t('agent.aiMode') }}</button>
        <router-link
          v-slot="{ navigate }"
          to="/flows"
          custom
        >
          <button
            role="tab"
            aria-selected="false"
            :disabled="busy"
            @click="navigate"
          ><i class="mdi mdi-vector-polyline" /> {{ t('agent.canvasMode') }}</button>
        </router-link>
      </div>

      <details class="cx-details" style="margin-bottom: 12px">
        <summary>{{ t('agent.history') }} ({{ runHistory.length }})</summary>
        <div class="cx-details__body">
          <div v-if="!runHistory.length" class="cx-muted">{{ t('agent.historyEmpty') }}</div>
          <div
            v-for="item in runHistory"
            :key="item.id"
            class="cx-row"
            :style="{
              padding: '7px 0',
              opacity: selectedHistoryId === item.id ? 1 : 0.82,
              borderTop: '1px solid rgb(var(--v-theme-outline-variant))',
            }"
          >
            <button class="cx-grow history-run" :disabled="busy" @click="showPersistedRun(item)">
              <span>{{ item.goal }}</span>
              <small>{{ item.status }} · {{ new Date(item.updatedAt).toLocaleString() }}</small>
            </button>
            <button
              v-if="item.status === 'FAILED' || item.status === 'CANCELLED'"
              class="cx-btn cx-btn--outline"
              :disabled="busy"
              @click="resumePersisted(item)"
            >{{ t('agent.resume') }}</button>
          </div>
        </div>
      </details>

      <!-- Banners -->
      <div v-if="errorMsg" class="cx-alert cx-alert--error" style="margin-bottom: 12px">
        <span class="cx-alert__body">{{ errorMsg }}</span>
        <button class="cx-iconbtn cx-iconbtn--sm" @click="errorMsg = null"><i class="mdi mdi-close" /></button>
      </div>
      <div v-else-if="summary && status === 'complete'" class="cx-alert cx-alert--success" style="margin-bottom: 12px">
        <span class="cx-alert__body">{{ summary }}</span>
      </div>

      <!-- Goal composer -->
      <div class="cx-composer" style="display: flex; align-items: flex-end; gap: 8px; margin-bottom: 12px">
        <select v-model="permissionMode" class="cx-select" style="width: 190px" :disabled="busy">
          <option value="ask-for-approval">{{ t('aichat.permissionAsk') }}</option>
          <option value="approve-for-me">{{ t('aichat.permissionAuto') }}</option>
          <option value="full-access">{{ t('aichat.permissionFullAccess') }}</option>
        </select>
        <textarea
          v-model="goal"
          rows="2"
          class="cx-grow"
          style="padding: 8px 0; min-height: 52px"
          :placeholder="t('agent.goalPlaceholder')"
          :disabled="busy"
        />
        <button
          v-if="busy"
          class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
          :title="t('agent.cancel')"
          @click="cancel"
        ><i class="mdi mdi-stop" /></button>
        <button
          v-else
          class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
          :disabled="!goal.trim()"
          :title="t('agent.run')"
          @click="run"
        ><i class="mdi mdi-play" /></button>
      </div>

      <!-- Status line -->
      <div v-if="statusLabel" class="cx-row" style="margin-bottom: 12px">
        <span v-if="busy" class="cx-spin" />
        <span class="cx-chip" :class="statusChipClass">{{ statusLabel }}</span>
      </div>

      <!-- Available tools -->
      <details v-if="tools.length" class="cx-details" style="margin-bottom: 12px">
        <summary>{{ t('agent.tools') }} ({{ tools.length }})</summary>
        <div class="cx-details__body">
          <div v-for="tool in tools" :key="tool.name" style="padding: 6px 0">
            <code>{{ tool.name }}</code>
            <div v-if="tool.localizedDescription || tool.description" class="cx-muted" style="font-size: 12px">{{ tool.localizedDescription || tool.description }}</div>
          </div>
        </div>
      </details>

      <!-- Live planner token stream -->
      <div v-if="planTokens && !plan" class="cx-card" style="margin-bottom: 12px">
        <pre class="mono" style="white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; max-height: 240px; overflow-y: auto; font-size: 12px">{{ planTokens }}</pre>
      </div>

      <!-- Plan display -->
      <div v-if="plan" class="cx-card" style="margin-bottom: 12px">
        <div v-if="plan.reasoning" class="cx-muted" style="margin-bottom: 14px; font-size: 13px; overflow-wrap: anywhere">
          {{ plan.reasoning }}
        </div>
        <div
          v-for="s in stepList.length ? stepList : plan.steps"
          :key="s.index"
          class="cx-row"
          style="align-items: flex-start; padding: 7px 0; border-top: 1px solid rgb(var(--v-theme-outline-variant))"
        >
          <span class="cx-muted" style="min-width: 56px; font-size: 12px">{{ t('agent.step', { n: s.index + 1 }) }}</span>
          <div class="cx-grow">
            <span v-if="s.toolName" style="font-weight: 600; margin-right: 8px">{{ s.toolName }}</span>
            <span>{{ s.description }}</span>
            <div v-if="stepRetries.get(s.index)?.length" class="agent-step-retries">
              <small v-for="retry in stepRetries.get(s.index)" :key="`${retry.nextAttempt}-${retry.createdAt ?? retry.delayMs}`">
                <i class="mdi mdi-refresh" />
                {{ t('agent.retryAttempt', { attempt: retry.nextAttempt, max: retry.maxAttempts, delay: retry.delayMs }) }}
                <span v-if="retry.error"> · {{ retry.error }}</span>
              </small>
            </div>
            <details v-if="stepResults.get(s.index)" class="agent-step-result">
              <summary>{{ t('agent.stepResult') }}</summary>
              <pre>{{ stepResults.get(s.index) }}</pre>
            </details>
          </div>
          <span class="cx-chip" :class="stepChipClass(s.status)">{{ s.status }}</span>
        </div>
      </div>

      <!-- Approval controls -->
      <div v-if="status === 'awaiting-plan' || status === 'awaiting-step'" class="cx-row" style="margin-bottom: 12px">
        <button class="cx-btn cx-btn--primary" @click="approve">{{ t('agent.approve') }}</button>
        <button class="cx-btn cx-btn--outline" @click="cancel">{{ t('agent.cancel') }}</button>
      </div>

      <!-- Empty hint -->
      <div v-if="status === 'idle' && !plan" class="cx-muted" style="text-align: center; margin-top: 24px">
        {{ t('agent.empty') }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.cx-page {
  max-width: 1480px;
  padding: 18px 18px 36px;
}

.agent-mode {
  width: fit-content;
  margin-bottom: 12px;
}

.agent-mode button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.history-run {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 0;
  color: inherit;
  text-align: left;
}

.history-run span,
.history-run small {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-step-result { margin-top: 4px; }
.agent-step-retries { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; color: rgb(var(--v-theme-warning)); }
.agent-step-retries small { white-space: normal; overflow-wrap: anywhere; }
.agent-step-result summary { color: rgb(var(--v-theme-primary)); font-size: 10px; cursor: pointer; user-select: none; }
.agent-step-result pre { max-height: 180px; margin: 5px 0 0; padding: 7px; overflow: auto; color: rgba(var(--v-theme-on-surface), .78); font-size: 10px; line-height: 1.45; white-space: pre-wrap; overflow-wrap: anywhere; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; background: rgb(var(--v-theme-surface-variant)); }
</style>
