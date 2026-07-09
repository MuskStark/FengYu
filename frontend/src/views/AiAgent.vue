<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { backendUrl, getToken } from '@/api/config'
import type { AgentPlan, AgentStep, AgentTool, AgentRunConfig } from '@/api/types'

/**
 * Minimal Plan-and-Execute agent UI (Task 20).
 *
 * Flow: goal textarea → POST /api/agent/run → open EventSource on
 * /api/agent/stream?runId=… → parse the backend's named SSE events
 * (plan_token / plan_ready / plan_approval_requested / step_start /
 * step_complete / step_approval_requested / complete / error) and update
 * reactive plan/steps/status. Approve/Cancel buttons drive the matching
 * endpoints. Canvas-based plan editing is deferred to Phase 2.
 *
 * The SSE wiring mirrors src/api/sse.ts: EventSource can't set headers, so the
 * token (when present) rides as a `token` query param alongside `runId`.
 */

const { t } = useI18n()

// ── reactive state ───────────────────────────────────────────────────────
const goal = ref('')
const planTokens = ref('') // streamed planner deltas (plan_token)
const plan = ref<AgentPlan | null>(null)
/** Per-index step bookkeeping. Keyed by step.index (from step_start/step_complete). */
const steps = ref<Map<number, AgentStep>>(new Map())
const tools = ref<AgentTool[]>([])
const runId = ref<string | null>(null)
type Status = 'idle' | 'planning' | 'awaiting-plan' | 'running' | 'awaiting-step' | 'complete' | 'error' | 'cancelled'
const status = ref<Status>('idle')
const summary = ref<string | null>(null)
const errorMsg = ref<string | null>(null)
let es: EventSource | null = null

// Default approval/recovery config (sent on /run). Hard-coded to plan-only
// approval for the minimal UI; step approval is surfaced but config stays simple.
const config: AgentRunConfig = {
  requirePlanApproval: true,
  requireStepApproval: false,
  replanOnFailure: false,
  maxReplans: 0,
}

const busy = computed(
  () =>
    status.value === 'planning' ||
    status.value === 'awaiting-plan' ||
    status.value === 'running' ||
    status.value === 'awaiting-step',
)

// Ordered step list (steps Map → array for the template).
const stepList = computed(() => Array.from(steps.value.values()).sort((a, b) => a.index - b.index))

// ── lifecycle ────────────────────────────────────────────────────────────
// Load the tool list once on mount for the "Available tools" hint.
void api.agentTools().then((list) => (tools.value = list ?? [])).catch(() => {/* best effort */})

onBeforeUnmount(() => closeStream())

// ── SSE wiring ───────────────────────────────────────────────────────────

/** Open the SSE stream for a runId and dispatch the backend's named events. */
function openStream(id: string) {
  closeStream()
  const params = new URLSearchParams({ runId: id })
  const token = getToken()
  if (token) params.set('token', token)
  const url = backendUrl(`/api/agent/stream?${params.toString()}`)

  es = new EventSource(url)

  const parse = <T>(ev: Event): T | null => {
    try {
      return JSON.parse((ev as MessageEvent).data) as T
    } catch {
      return null
    }
  }

  // plan_token: a streamed planner delta — append to the live plan preview.
  es.addEventListener('plan_token', (ev) => {
    const d = parse<{ delta: string }>(ev)
    if (d) planTokens.value += d.delta
  })

  // plan_ready: the structured plan arrives; clear the token preview.
  es.addEventListener('plan_ready', (ev) => {
    const d = parse<{ goal: string; steps?: AgentStep[]; reasoning: string }>(ev)
    if (!d) return
    const ps = Array.isArray(d.steps) ? d.steps : []
    plan.value = { goal: d.goal, steps: ps, reasoning: d.reasoning ?? '' }
    // Seed step bookkeeping so the UI can show pending steps immediately.
    for (const s of ps) steps.value.set(s.index, { ...s, status: s.status || 'pending' })
    planTokens.value = ''
    if (config.requirePlanApproval) status.value = 'awaiting-plan'
    else status.value = 'running'
  })

  es.addEventListener('plan_approval_requested', () => {
    status.value = 'awaiting-plan'
  })

  es.addEventListener('step_start', (ev) => {
    const d = parse<{ index: number }>(ev)
    if (!d) return
    const existing = steps.value.get(d.index)
    if (existing) existing.status = 'running'
    else steps.value.set(d.index, { index: d.index, toolName: '', description: '', status: 'running' })
    status.value = 'running'
  })

  es.addEventListener('step_complete', (ev) => {
    const d = parse<{ index: number; result: string }>(ev)
    if (!d) return
    const existing = steps.value.get(d.index)
    if (existing) existing.status = 'complete'
    else steps.value.set(d.index, { index: d.index, toolName: '', description: d.result ?? '', status: 'complete' })
    status.value = 'running'
  })

  es.addEventListener('step_approval_requested', (ev) => {
    const d = parse<{ index: number }>(ev)
    if (d) status.value = 'awaiting-step'
  })

  es.addEventListener('complete', (ev) => {
    const d = parse<{ summary: string }>(ev)
    summary.value = d?.summary ?? ''
    status.value = 'complete'
    closeStream()
  })

  // Named "error" event from the backend carries a JSON message; the native
  // EventSource error (connection drop) has no parseable data.
  es.addEventListener('error', (ev) => {
    const d = parse<{ message: string }>(ev)
    if (d?.message) {
      errorMsg.value = d.message
      status.value = 'error'
      closeStream()
    } else if (status.value !== 'complete' && status.value !== 'cancelled' && status.value !== 'error') {
      // Native connection drop — surface a generic message but don't necessarily fail the run.
      errorMsg.value = t('agent.failed')
    }
  })

  es.addEventListener('open', () => {
    if (status.value === 'idle') status.value = 'planning'
  })
}

function closeStream() {
  if (es) {
    es.close()
    es = null
  }
}

// ── actions ──────────────────────────────────────────────────────────────

async function run() {
  const g = goal.value.trim()
  if (!g || busy.value) return
  // Reset for a fresh run.
  errorMsg.value = null
  summary.value = null
  plan.value = null
  planTokens.value = ''
  steps.value = new Map()
  status.value = 'planning'

  try {
    const { runId: id } = await api.agentRun({ goal: g, config })
    runId.value = id
    openStream(id)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    status.value = 'error'
  }
}

async function approve() {
  if (!runId.value) return
  try {
    // No plan editing in the minimal UI — pass undefined so the gate releases as-is.
    await api.agentApprove(runId.value)
    if (status.value === 'awaiting-plan' || status.value === 'awaiting-step') status.value = 'running'
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}

async function cancel() {
  if (!runId.value) {
    status.value = 'cancelled'
    return
  }
  try {
    await api.agentCancel(runId.value)
  } finally {
    status.value = 'cancelled'
    closeStream()
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
</script>

<template>
  <div class="agent-page">
    <header class="agent-head">
      <h1 class="section-header">{{ t('agent.title') }}</h1>
    </header>

    <div v-if="errorMsg" class="banner error">
      {{ errorMsg }}
    </div>
    <div v-else-if="summary && status === 'complete'" class="banner success">
      {{ summary }}
    </div>

    <!-- Goal composer -->
    <div class="composer">
      <textarea
        v-model="goal"
        class="sk-field input"
        rows="3"
        :placeholder="t('agent.goalPlaceholder')"
        :disabled="busy"
      />
      <button
        v-if="busy"
        class="sk-btn-secondary"
        @click="cancel"
      >
        {{ t('agent.cancel') }}
      </button>
      <button
        v-else
        class="sk-btn-primary"
        :disabled="!goal.trim()"
        @click="run"
      >
        {{ t('agent.run') }}
      </button>
    </div>

    <!-- Status line -->
    <div v-if="statusLabel" class="status-line">
      <span class="status-dot" :class="status" />
      {{ statusLabel }}
    </div>

    <!-- Available tools hint -->
    <details v-if="tools.length" class="tools">
      <summary>{{ t('agent.tools') }} ({{ tools.length }})</summary>
      <ul class="tool-list">
        <li v-for="tool in tools" :key="tool.name">
          <code>{{ tool.name }}</code>
          <span v-if="tool.description" class="tool-desc">{{ tool.description }}</span>
        </li>
      </ul>
    </details>

    <!-- Live planner token stream (before plan_ready) -->
    <pre v-if="planTokens && !plan" class="plan-preview">{{ planTokens }}</pre>

    <!-- Plan display -->
    <section v-if="plan" class="plan">
      <div class="plan-reasoning" v-if="plan.reasoning">{{ plan.reasoning }}</div>
      <ol class="step-list">
        <li
          v-for="s in stepList.length ? stepList : plan.steps"
          :key="s.index"
          class="step"
          :class="s.status"
        >
          <span class="step-index">{{ t('agent.step', { n: s.index + 1 }) }}</span>
          <span class="step-tool" v-if="s.toolName">{{ s.toolName }}</span>
          <span class="step-desc">{{ s.description }}</span>
          <span class="step-status">{{ s.status }}</span>
        </li>
      </ol>
    </section>

    <!-- Approval controls -->
    <div v-if="status === 'awaiting-plan' || status === 'awaiting-step'" class="approval">
      <button class="sk-btn-primary" @click="approve">{{ t('agent.approve') }}</button>
      <button class="sk-btn-secondary" @click="cancel">{{ t('agent.cancel') }}</button>
    </div>

    <!-- Empty hint -->
    <div v-if="status === 'idle' && !plan" class="empty">{{ t('agent.empty') }}</div>
  </div>
</template>

<style scoped>
.agent-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  padding: 16px 20px;
}
.agent-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.agent-head h1 {
  margin: 0;
}
.banner {
  border-radius: 8px;
  padding: 8px 12px;
  border: 1px solid;
  overflow-wrap: anywhere;
}
.banner.error {
  background: var(--sk-danger-soft);
  color: var(--sk-danger);
  border-color: var(--sk-danger);
}
.banner.success {
  background: var(--sk-success-soft, var(--sk-accent-soft));
  color: var(--sk-success, var(--sk-accent));
  border-color: var(--sk-success, var(--sk-accent));
}
.composer {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.input {
  flex: 1;
  resize: none;
  font-family: inherit;
}
.status-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--sk-text-secondary);
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sk-text-secondary);
  display: inline-block;
}
.status-dot.planning,
.status-dot.running {
  background: var(--sk-accent);
}
.status-dot.awaiting-plan,
.status-dot.awaiting-step {
  background: var(--sk-warning, #f0ad4e);
}
.status-dot.complete {
  background: var(--sk-success, var(--sk-accent));
}
.status-dot.error {
  background: var(--sk-danger);
}
.status-dot.cancelled {
  background: var(--sk-text-secondary);
}
.tools {
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--sk-text-secondary);
}
.tools summary {
  cursor: pointer;
}
.tool-list {
  margin: 6px 0 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.tool-list code {
  color: var(--sk-text);
}
.tool-desc {
  margin-left: 6px;
  opacity: 0.8;
}
.plan-preview {
  margin: 0;
  padding: 10px 12px;
  background: var(--sk-bg-elevated);
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 240px;
  overflow-y: auto;
}
.plan {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.plan-reasoning {
  font-size: 13px;
  color: var(--sk-text-secondary);
  background: var(--sk-bg-hover);
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  padding: 8px 10px;
  overflow-wrap: anywhere;
}
.step-list {
  margin: 0;
  padding-left: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.step {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  background: var(--sk-bg-elevated);
}
.step.running {
  border-left: 3px solid var(--sk-accent);
}
.step.complete {
  border-left: 3px solid var(--sk-success, var(--sk-accent));
}
.step.pending {
  border-left: 3px solid var(--sk-border);
}
.step-index {
  font-size: 11px;
  color: var(--sk-text-secondary);
  min-width: 56px;
}
.step-tool {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  color: var(--sk-accent);
}
.step-desc {
  flex: 1;
  overflow-wrap: anywhere;
}
.step-status {
  font-size: 11px;
  text-transform: uppercase;
  color: var(--sk-text-secondary);
}
.approval {
  display: flex;
  gap: 8px;
}
.empty {
  color: var(--sk-text-secondary);
  text-align: center;
  margin-top: 24px;
}
</style>
