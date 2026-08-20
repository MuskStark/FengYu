<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type {
  AgentPlan,
  AgentRunSummary,
  AgentScheduleSummary,
  AgentStep,
  AgentTaskSummary,
} from '@/api/types'
import type { AgentRunStatus } from '@/components/agent/agentRunStream'

/**
 * Right-hand execution panel of the flow builder: live run status + step
 * results, run history (search / fork / rewind), background tasks and
 * workflow schedules.
 */
const props = defineProps<{
  status: AgentRunStatus
  runId: string | null
  plan: AgentPlan | null
  stepList: AgentStep[]
  stepResults: Map<number, string>
  summary: string | null
  errorMsg: string | null
  busy: boolean
  runHistory: AgentRunSummary[]
  selectedHistoryId: string | null
  backgroundTasks: AgentTaskSummary[]
  schedules: AgentScheduleSummary[]
}>()
const emit = defineEmits<{
  close: []
  approve: []
  cancel: []
  'show-run': [item: AgentRunSummary]
  fork: [id: string]
  rewind: [index: number]
  search: [query: string]
  'refresh-tasks': []
  kill: [taskId: string]
  'remove-schedule': [scheduleId: string]
}>()

const { t } = useI18n()
const historyQuery = ref('')

const statusLabel = computed(() => {
  switch (props.status) {
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

const statusChipClass = computed(() => {
  switch (props.status) {
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
  if (s === 'complete') return 'cx-chip--success'
  if (s === 'failed') return 'cx-chip--error'
  // skipped keeps the default muted chip — control flow omitted it, nothing failed.
  return ''
}
</script>

<template>
  <div class="flow-execution">
    <div class="flow-execution__title">
      {{ t('agent.runPanel') }}
      <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('flows.close')" @click="emit('close')"><i class="mdi mdi-close" /></button>
    </div>
    <div v-if="statusLabel" class="flow-execution__status">
      <span v-if="busy" class="cx-spin" />
      <span class="cx-chip" :class="statusChipClass">{{ statusLabel }}</span>
    </div>
    <div v-if="errorMsg" class="cx-alert cx-alert--error"><span class="cx-alert__body">{{ errorMsg }}</span></div>
    <div v-if="summary && status === 'complete'" class="cx-alert cx-alert--success"><span class="cx-alert__body">{{ summary }}</span></div>
    <div v-if="plan" class="flow-execution__steps">
      <div v-for="s in stepList.length ? stepList : plan.steps" :key="s.index" class="flow-execution__step">
        <span class="flow-execution__step-index">{{ s.index + 1 }}</span>
        <span class="flow-execution__step-body">
          <span class="flow-execution__step-head"><strong>{{ s.toolName }}</strong><small>{{ s.description }}</small></span>
          <details v-if="stepResults.get(s.index)" class="flow-step-result">
            <summary>{{ t('agent.stepResult') }}</summary>
            <pre>{{ stepResults.get(s.index) }}</pre>
          </details>
          <button
            v-if="!busy && runId && stepResults.get(s.index) !== undefined"
            class="flow-rewind-btn"
            :title="t('agent.rewindToStep')"
            @click="emit('rewind', s.index)"
          ><i class="mdi mdi-undo-variant" /> {{ t('agent.rewindFromHere') }}</button>
        </span>
        <span class="cx-chip" :class="stepChipClass(s.status)">{{ s.status }}</span>
      </div>
    </div>
    <div v-if="status === 'awaiting-plan' || status === 'awaiting-step'" class="cx-row">
      <button class="cx-btn cx-btn--primary" @click="emit('approve')">{{ t('agent.approve') }}</button>
      <button class="cx-btn cx-btn--outline" @click="emit('cancel')">{{ t('agent.cancel') }}</button>
    </div>

    <div class="flow-execution__section-title">{{ t('agent.history') }}</div>
    <div class="flow-history-search">
      <i class="mdi mdi-magnify" />
      <input
        v-model="historyQuery"
        :placeholder="t('agent.historySearchPlaceholder')"
        @keyup.enter="emit('search', historyQuery.trim())"
      >
      <button :title="t('agent.historySearch')" @click="emit('search', historyQuery.trim())"><i class="mdi mdi-magnify" /></button>
    </div>
    <div v-if="!runHistory.length" class="cx-muted flow-execution__empty">{{ t('agent.historyEmpty') }}</div>
    <div v-for="item in runHistory" :key="item.id" class="flow-history-row">
      <button
        class="flow-history-row__main"
        :style="{ opacity: selectedHistoryId === item.id ? 1 : .82 }"
        @click="emit('show-run', item)"
      >
        <span>{{ item.goal }}</span><small>{{ item.status }} · {{ new Date(item.updatedAt).toLocaleString() }}</small>
      </button>
      <button
        v-if="item.status === 'COMPLETED' || item.status === 'FAILED' || item.status === 'CANCELLED'"
        class="cx-iconbtn cx-iconbtn--sm"
        :title="t('agent.forkRun')"
        :disabled="busy"
        @click="emit('fork', item.id)"
      ><i class="mdi mdi-source-branch" /></button>
    </div>

    <div v-if="backgroundTasks.length" class="flow-execution__section-title">{{ t('agent.backgroundTasks') }}</div>
    <div v-for="task in backgroundTasks" :key="task.taskId" class="flow-history-row">
      <button class="flow-history-row__main" @click="emit('refresh-tasks')">
        <span>{{ task.description }}</span>
        <small>{{ task.status }} · {{ task.kind }}</small>
      </button>
      <button
        v-if="task.status === 'running'"
        class="cx-iconbtn cx-iconbtn--sm"
        :title="t('agent.killTask')"
        @click="emit('kill', task.taskId)"
      ><i class="mdi mdi-stop" /></button>
    </div>

    <div v-if="schedules.length" class="flow-execution__section-title">{{ t('agent.schedules') }}</div>
    <div v-for="schedule in schedules" :key="schedule.scheduleId" class="flow-history-row">
      <button class="flow-history-row__main">
        <span>{{ schedule.workflowId }}</span>
        <small>{{ t('agent.scheduleEvery', { n: schedule.intervalSeconds }) }} · {{ t('agent.scheduleFires', { n: schedule.fires }) }}</small>
      </button>
      <button
        class="cx-iconbtn cx-iconbtn--sm"
        :title="t('agent.deleteSchedule')"
        @click="emit('remove-schedule', schedule.scheduleId)"
      ><i class="mdi mdi-delete-outline" /></button>
    </div>
  </div>
</template>

<style scoped>
.flow-execution {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.flow-execution__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
}

.flow-execution__status { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.flow-execution__steps { display: flex; flex-direction: column; gap: 7px; margin: 12px 0 18px; }
.flow-execution__step { display: flex; gap: 8px; align-items: center; padding: 9px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 9px; }
.flow-execution__step-body { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.flow-execution__step-head { display: flex; min-width: 0; flex-direction: column; }
.flow-execution__step small { overflow: hidden; color: rgba(var(--v-theme-on-surface), .6); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.flow-execution__step-index { display: grid; place-items: center; width: 23px; height: 23px; color: rgb(var(--v-theme-primary)); font-size: 10px; border-radius: 50%; background: rgba(var(--v-theme-primary), .12); }
.flow-step-result { margin-top: 4px; }
.flow-step-result summary { color: rgb(var(--v-theme-primary)); font-size: 10px; cursor: pointer; user-select: none; }
.flow-step-result pre { max-height: 180px; margin: 5px 0 0; padding: 7px; overflow: auto; color: rgba(var(--v-theme-on-surface), .78); font-size: 10px; line-height: 1.45; white-space: pre-wrap; overflow-wrap: anywhere; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; background: rgb(var(--v-theme-surface-variant)); }
.flow-rewind-btn { display: inline-flex; gap: 4px; align-items: center; margin-top: 4px; padding: 2px 8px; color: rgb(var(--v-theme-primary)); font-size: 10px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; background: transparent; cursor: pointer; }

.flow-execution__section-title { margin: 18px 0 7px; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; }
.flow-history-search { display: flex; gap: 6px; align-items: center; padding: 6px 0; }
.flow-history-search i { color: rgba(var(--v-theme-on-surface), .5); }
.flow-history-search input { flex: 1; min-width: 0; padding: 4px 6px; color: inherit; font: inherit; font-size: 12px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; background: rgb(var(--v-theme-surface)); }
.flow-history-search button { border: 0; background: transparent; color: rgba(var(--v-theme-on-surface), .6); cursor: pointer; }

.flow-history-row { display: flex; width: 100%; align-items: center; gap: 4px; padding: 8px 0; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.flow-history-row__main { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; padding: 0; color: inherit; text-align: left; border: 0; background: transparent; cursor: pointer; }
.flow-history-row__main span,
.flow-history-row__main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.flow-history-row__main small { color: rgba(var(--v-theme-on-surface), .55); font-size: 10px; }

.flow-execution__empty { padding: 20px 4px; text-align: center; font-size: 12px; }
</style>
