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

// Codex chip class per run status / per step status.
const statusChipClass = computed(() => {
  switch (status.value) {
    case 'planning':
    case 'running':
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
  if (s === 'complete') return 'cx-chip--success'
  return ''
}
</script>

<template>
  <div style="flex: 1 1 auto; min-height: 0; overflow-y: auto">
    <div class="cx-page">
      <h1 class="cx-page-title">{{ t('agent.title') }}</h1>

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
            <div v-if="tool.description" class="cx-muted" style="font-size: 12px">{{ tool.description }}</div>
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
