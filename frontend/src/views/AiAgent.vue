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
  <v-container fluid class="d-flex flex-column h-100">
    <!-- Header -->
    <div class="d-flex align-center justify-space-between mb-2">
      <h1 class="text-h5">{{ t('agent.title') }}</h1>
    </div>

    <!-- Banners -->
    <v-alert
      v-if="errorMsg"
      type="error"
      variant="tonal"
      closable
      class="mb-2"
    >{{ errorMsg }}</v-alert>
    <v-alert
      v-else-if="summary && status === 'complete'"
      type="success"
      variant="tonal"
      closable
      class="mb-2"
    >{{ summary }}</v-alert>

    <!-- Goal composer -->
    <div class="d-flex ga-2 align-end mb-2">
      <v-textarea
        v-model="goal"
        class="flex-grow-1"
        rows="3"
        auto-grow
        variant="outlined"
        hide-details
        :placeholder="t('agent.goalPlaceholder')"
        :disabled="busy"
      />
      <v-btn
        v-if="busy"
        variant="outlined"
        prepend-icon="mdi-stop"
        @click="cancel"
      >{{ t('agent.cancel') }}</v-btn>
      <v-btn
        v-else
        color="primary"
        prepend-icon="mdi-play"
        :disabled="!goal.trim()"
        @click="run"
      >{{ t('agent.run') }}</v-btn>
    </div>

    <!-- Status line -->
    <div v-if="statusLabel" class="d-flex align-center ga-2 mb-2">
      <v-progress-circular
        v-if="busy"
        indeterminate
        size="16"
        width="2"
        color="primary"
      />
      <v-chip
        size="x-small"
        :color="{ planning: 'primary', running: 'primary', 'awaiting-plan': 'warning', 'awaiting-step': 'warning', complete: 'success', error: 'error', cancelled: 'default', idle: 'default' }[status]"
      >{{ statusLabel }}</v-chip>
    </div>

    <!-- Available tools hint -->
    <v-expansion-panels v-if="tools.length" variant="accordion" class="mb-2">
      <v-expansion-panel>
        <v-expansion-panel-title>
          {{ t('agent.tools') }} ({{ tools.length }})
        </v-expansion-panel-title>
        <v-expansion-panel-text>
          <v-list density="compact" class="bg-transparent">
            <v-list-item v-for="tool in tools" :key="tool.name">
              <v-list-item-title><code>{{ tool.name }}</code></v-list-item-title>
              <v-list-item-subtitle v-if="tool.description">{{ tool.description }}</v-list-item-subtitle>
            </v-list-item>
          </v-list>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>

    <!-- Live planner token stream (before plan_ready) -->
    <v-card v-if="planTokens && !plan" variant="outlined" rounded="lg" class="mb-2">
      <v-card-text>
        <pre class="text-body-2" style="white-space: pre-wrap; overflow-wrap: anywhere; margin: 0;">{{ planTokens }}</pre>
      </v-card-text>
    </v-card>

    <!-- Plan display -->
    <v-card v-if="plan" variant="tonal" rounded="lg" class="mb-2">
      <v-card-text>
        <div
          v-if="plan.reasoning"
          class="text-body-2 text-medium-emphasis mb-3"
          style="overflow-wrap: anywhere;"
        >{{ plan.reasoning }}</div>
        <v-list density="compact" class="bg-transparent">
          <v-list-item
            v-for="s in stepList.length ? stepList : plan.steps"
            :key="s.index"
          >
            <template #prepend>
              <span class="text-caption text-medium-emphasis" style="min-width: 56px;">
                {{ t('agent.step', { n: s.index + 1 }) }}
              </span>
            </template>
            <v-list-item-title>
              <span v-if="s.toolName" class="font-weight-medium mr-2">{{ s.toolName }}</span>
              <span>{{ s.description }}</span>
            </v-list-item-title>
            <template #append>
              <v-chip
                size="x-small"
                :color="{ pending: 'default', running: 'primary', complete: 'success' }[s.status] ?? 'default'"
              >{{ s.status }}</v-chip>
            </template>
          </v-list-item>
        </v-list>
      </v-card-text>
    </v-card>

    <!-- Approval controls -->
    <div v-if="status === 'awaiting-plan' || status === 'awaiting-step'" class="d-flex ga-2 mb-2">
      <v-btn color="primary" @click="approve">{{ t('agent.approve') }}</v-btn>
      <v-btn variant="outlined" @click="cancel">{{ t('agent.cancel') }}</v-btn>
    </div>

    <!-- Empty hint -->
    <div v-if="status === 'idle' && !plan" class="text-medium-emphasis text-center mt-6">
      {{ t('agent.empty') }}
    </div>
  </v-container>
</template>
