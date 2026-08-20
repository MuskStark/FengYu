<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type {
  AgentPlan,
  AgentRunSummary,
  AgentScheduleSummary,
  AgentStep,
  AgentStepRetryEvent,
  AgentTaskCapacity,
  AgentTaskSummary,
  WorkflowWebhookDeliverySummary,
  WorkflowWebhookTriggerCreated,
  WorkflowWebhookTriggerSummary,
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
  stepRetries: Map<number, AgentStepRetryEvent[]>
  summary: string | null
  errorMsg: string | null
  busy: boolean
  runHistory: AgentRunSummary[]
  selectedHistoryId: string | null
  backgroundTasks: AgentTaskSummary[]
  backgroundTaskCapacity: AgentTaskCapacity | null
  schedules: AgentScheduleSummary[]
  webhookTriggers: WorkflowWebhookTriggerSummary[]
  webhookDeliveries: Record<string, WorkflowWebhookDeliverySummary[]>
  webhookCredentials: WorkflowWebhookTriggerCreated | null
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
  'rotate-webhook': [triggerId: string]
  'remove-webhook': [triggerId: string]
  'load-webhook-deliveries': [triggerId: string]
  'clear-webhook-credentials': []
}>()

const { t } = useI18n()
const historyQuery = ref('')
const webhookCopied = ref(false)
const expandedWebhook = ref<string | null>(null)

/** Priority class holding the oldest queued task, so the delay alert names the offender. */
const delayedQueuedPriority = computed<string | null>(() => {
  const capacity = props.backgroundTaskCapacity
  if (!capacity) return null
  const oldest: Array<[string, number]> = [
    ['interactive', capacity.oldestInteractiveQueueWaitMs],
    ['normal', capacity.oldestNormalQueueWaitMs],
    ['batch', capacity.oldestBatchQueueWaitMs],
  ]
  const worst = oldest.reduce((left, right) => (right[1] > left[1] ? right : left))
  return worst[1] > 0 ? worst[0] : null
})

function toggleWebhookDeliveries(triggerId: string) {
  if (expandedWebhook.value === triggerId) {
    expandedWebhook.value = null
    return
  }
  expandedWebhook.value = triggerId
  emit('load-webhook-deliveries', triggerId)
}

function webhookDeliveryStatusClass(status: string): string {
  if (status === 'COMPLETED') return 'cx-chip--success'
  if (status === 'FAILED' || status === 'INTERRUPTED') return 'cx-chip--error'
  if (status === 'CANCELLED') return 'cx-chip--warn'
  return 'cx-chip--primary'
}

function webhookDeliveryStatusLabel(status: string): string {
  const key = ({
    CLAIMED: 'claimed', QUEUED: 'queued', SUBMITTED: 'submitted', COMPLETED: 'completed', FAILED: 'failed',
    CANCELLED: 'cancelled', INTERRUPTED: 'interrupted',
  } as Record<string, string>)[status]
  return key ? t(`agent.webhookDeliveryStatus.${key}`) : status
}

function backgroundTaskStatusLabel(status: string): string {
  return ['queued', 'running', 'completed', 'failed', 'cancelled'].includes(status)
    ? t(`agent.backgroundTaskStatus.${status}`)
    : status
}

function backgroundTaskPriorityLabel(priority: string): string {
  return ['interactive', 'normal', 'batch'].includes(priority)
    ? t(`agent.backgroundTaskPriority.${priority}`)
    : priority
}

function formatDuration(ms: number): string {
  const safeMs = Math.max(0, ms)
  if (safeMs < 1_000) return `${Math.round(safeMs)} ms`
  if (safeMs < 60_000) return `${(safeMs / 1_000).toFixed(safeMs < 10_000 ? 1 : 0)} s`
  return `${(safeMs / 60_000).toFixed(1)} min`
}

function webhookDeliveryDuration(delivery: WorkflowWebhookDeliverySummary): string | null {
  if (!delivery.completedAt) return null
  const ms = Math.max(0, new Date(delivery.completedAt).getTime()
    - new Date(delivery.acceptedAt).getTime())
  return formatDuration(ms)
}

async function copyWebhookCommand() {
  const created = props.webhookCredentials
  if (!created) return
  const url = `${window.location.origin}${created.endpoint}`
  const command = `curl -X POST '${url}' -H 'Content-Type: application/json' -H '${created.secretHeader}: ${created.secret}' -H '${created.eventIdHeader}: unique-event-id' -d '{}'`
  try {
    await navigator.clipboard.writeText(command)
    webhookCopied.value = true
    window.setTimeout(() => { webhookCopied.value = false }, 1800)
  } catch {
    // Clipboard APIs may be unavailable in a non-secure browser context.
  }
}

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
  if (s === 'retrying') return 'cx-chip--warn'
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
          <div v-if="stepRetries.get(s.index)?.length" class="flow-step-retries">
            <small v-for="retry in stepRetries.get(s.index)" :key="`${retry.nextAttempt}-${retry.createdAt ?? retry.delayMs}`">
              <i class="mdi mdi-refresh" />
              {{ t('agent.retryAttempt', { attempt: retry.nextAttempt, max: retry.maxAttempts, delay: retry.delayMs }) }}
              <span v-if="retry.error"> · {{ retry.error }}</span>
            </small>
          </div>
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

    <div v-if="backgroundTasks.length || backgroundTaskCapacity" class="flow-execution__section-title flow-execution__section-title--metric">
      <span>{{ t('agent.backgroundTasks') }}</span>
      <small v-if="backgroundTaskCapacity">{{ t('agent.backgroundTaskCapacity', {
        running: backgroundTaskCapacity.running,
        runningLimit: backgroundTaskCapacity.runningLimit,
        queued: backgroundTaskCapacity.queued,
        queueLimit: backgroundTaskCapacity.queueLimit,
        ownedQueued: backgroundTaskCapacity.ownedQueued,
        ownerQueueLimit: backgroundTaskCapacity.ownerQueueLimit,
        interactive: backgroundTaskCapacity.queuedInteractive,
        normal: backgroundTaskCapacity.queuedNormal,
        batch: backgroundTaskCapacity.queuedBatch,
      }) }}</small>
    </div>
    <div
      v-if="backgroundTaskCapacity?.saturated || backgroundTaskCapacity?.ownerSaturated || (backgroundTaskCapacity?.oldestQueueWaitMs ?? 0) >= 30_000"
      class="flow-task-pressure"
    >
      <i class="mdi mdi-alert-outline" />
      <span v-if="backgroundTaskCapacity?.saturated">{{ t('agent.backgroundTaskSaturated') }}</span>
      <span v-else-if="backgroundTaskCapacity?.ownerSaturated">{{ t('agent.backgroundTaskOwnerSaturated') }}</span>
      <span v-else>{{ t('agent.backgroundTaskDelayed', {
        duration: formatDuration(backgroundTaskCapacity?.oldestQueueWaitMs ?? 0),
        priority: delayedQueuedPriority ? backgroundTaskPriorityLabel(delayedQueuedPriority) : '',
      }) }}</span>
    </div>
    <div v-for="task in backgroundTasks" :key="task.taskId" class="flow-history-row">
      <button class="flow-history-row__main" @click="emit('refresh-tasks')">
        <span>{{ task.description }}</span>
        <small>{{ backgroundTaskStatusLabel(task.status) }} · {{ backgroundTaskPriorityLabel(task.priority) }} · {{ task.kind }} · {{ new Date(task.completedAt ?? task.createdAt).toLocaleString() }}</small>
        <small v-if="task.queueWaitMs !== undefined">
          {{ t('agent.backgroundTaskQueueWait', { duration: formatDuration(task.queueWaitMs) }) }}
          <template v-if="task.runDurationMs !== undefined"> · {{ t('agent.backgroundTaskRunDuration', { duration: formatDuration(task.runDurationMs) }) }}</template>
        </small>
      </button>
      <button
        v-if="task.status === 'queued' || task.status === 'running'"
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
        <small>{{ t('agent.scheduleNext', { time: new Date(schedule.nextFireAt).toLocaleString() }) }}</small>
        <small v-if="schedule.missedFires">{{ t('agent.scheduleMissed', { n: schedule.missedFires }) }}</small>
        <small v-if="schedule.lastError" class="flow-schedule-error">{{ schedule.lastError }}</small>
      </button>
      <button
        class="cx-iconbtn cx-iconbtn--sm"
        :title="t('agent.deleteSchedule')"
        @click="emit('remove-schedule', schedule.scheduleId)"
      ><i class="mdi mdi-delete-outline" /></button>
    </div>

    <div v-if="webhookTriggers.length || webhookCredentials" class="flow-execution__section-title">{{ t('agent.webhookTriggers') }}</div>
    <div v-if="webhookCredentials" class="flow-webhook-secret">
      <div>
        <strong><i class="mdi mdi-key-outline" /> {{ t('agent.webhookSecretOnce') }}</strong>
        <button class="cx-iconbtn cx-iconbtn--sm" :title="t('flows.close')" @click="emit('clear-webhook-credentials')"><i class="mdi mdi-close" /></button>
      </div>
      <code>{{ webhookCredentials.endpoint }}</code>
      <code>{{ webhookCredentials.secretHeader }}: {{ webhookCredentials.secret }}</code>
      <button class="cx-btn cx-btn--outline" @click="copyWebhookCommand"><i class="mdi mdi-content-copy" /> {{ webhookCopied ? t('common.copied') : t('agent.copyWebhookCurl') }}</button>
    </div>
    <template v-for="hook in webhookTriggers" :key="hook.triggerId">
      <div class="flow-history-row">
        <button class="flow-history-row__main" @click="toggleWebhookDeliveries(hook.triggerId)">
          <span><i class="mdi" :class="expandedWebhook === hook.triggerId ? 'mdi-chevron-down' : 'mdi-chevron-right'" /> {{ hook.name }}</span>
          <small>{{ hook.endpoint }} · {{ t('agent.webhookFires', { n: hook.fires }) }}</small>
          <small v-if="hook.lastFireAt">{{ new Date(hook.lastFireAt).toLocaleString() }}</small>
          <small v-if="hook.lastError" class="flow-schedule-error">{{ hook.lastError }}</small>
        </button>
        <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.rotateWebhookSecret')" @click="emit('rotate-webhook', hook.triggerId)"><i class="mdi mdi-key-change" /></button>
        <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.deleteWebhook')" @click="emit('remove-webhook', hook.triggerId)"><i class="mdi mdi-delete-outline" /></button>
      </div>
      <div v-if="expandedWebhook === hook.triggerId" class="flow-webhook-deliveries">
        <div class="flow-webhook-deliveries__head">
          <strong>{{ t('agent.webhookDeliveryHistory') }}</strong>
          <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.webhookRefreshDeliveries')" @click="emit('load-webhook-deliveries', hook.triggerId)"><i class="mdi mdi-refresh" /></button>
        </div>
        <div v-if="!webhookDeliveries[hook.triggerId]?.length" class="cx-muted flow-webhook-deliveries__empty">{{ t('agent.webhookNoDeliveries') }}</div>
        <div v-for="(delivery, index) in webhookDeliveries[hook.triggerId]" :key="`${delivery.taskId ?? delivery.acceptedAt}-${index}`" class="flow-webhook-delivery">
          <div>
            <span class="cx-chip" :class="webhookDeliveryStatusClass(delivery.status)">{{ webhookDeliveryStatusLabel(delivery.status) }}</span>
            <small>{{ new Date(delivery.acceptedAt).toLocaleString() }}</small>
          </div>
          <small>
            {{ delivery.idempotencyKeyPresent ? t('agent.webhookEventKeyed') : t('agent.webhookEventUnkeyed') }}
            <template v-if="webhookDeliveryDuration(delivery)"> · {{ webhookDeliveryDuration(delivery) }}</template>
          </small>
          <small v-if="delivery.taskId">{{ t('agent.webhookDeliveryTask') }}: {{ delivery.taskId }}</small>
          <small v-if="delivery.error" class="flow-schedule-error">{{ delivery.error }}</small>
        </div>
      </div>
    </template>
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
.flow-step-retries { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; color: rgb(var(--v-theme-warning)); }
.flow-step-retries small { overflow: visible; color: inherit; white-space: normal; overflow-wrap: anywhere; }
.flow-step-result summary { color: rgb(var(--v-theme-primary)); font-size: 10px; cursor: pointer; user-select: none; }
.flow-step-result pre { max-height: 180px; margin: 5px 0 0; padding: 7px; overflow: auto; color: rgba(var(--v-theme-on-surface), .78); font-size: 10px; line-height: 1.45; white-space: pre-wrap; overflow-wrap: anywhere; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; background: rgb(var(--v-theme-surface-variant)); }
.flow-rewind-btn { display: inline-flex; gap: 4px; align-items: center; margin-top: 4px; padding: 2px 8px; color: rgb(var(--v-theme-primary)); font-size: 10px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; background: transparent; cursor: pointer; }

.flow-execution__section-title { margin: 18px 0 7px; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; }
.flow-execution__section-title--metric { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.flow-execution__section-title--metric small { color: rgba(var(--v-theme-on-surface), .55); font-size: 9px; font-weight: 500; letter-spacing: 0; text-transform: none; }
.flow-task-pressure { display: flex; gap: 5px; align-items: center; margin: 0 0 7px; padding: 6px 7px; color: rgb(var(--v-theme-warning)); font-size: 9px; border: 1px solid rgba(var(--v-theme-warning), .4); border-radius: 7px; background: rgba(var(--v-theme-warning), .08); }
.flow-history-search { display: flex; gap: 6px; align-items: center; padding: 6px 0; }
.flow-history-search i { color: rgba(var(--v-theme-on-surface), .5); }
.flow-history-search input { flex: 1; min-width: 0; padding: 4px 6px; color: inherit; font: inherit; font-size: 12px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; background: rgb(var(--v-theme-surface)); }
.flow-history-search button { border: 0; background: transparent; color: rgba(var(--v-theme-on-surface), .6); cursor: pointer; }

.flow-history-row { display: flex; width: 100%; align-items: center; gap: 4px; padding: 8px 0; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.flow-history-row__main { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; padding: 0; color: inherit; text-align: left; border: 0; background: transparent; cursor: pointer; }
.flow-history-row__main span,
.flow-history-row__main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.flow-history-row__main .flow-schedule-error { color: rgb(var(--v-theme-error)); }
.flow-webhook-secret { display: grid; gap: 7px; margin-bottom: 8px; padding: 10px; border: 1px solid rgba(var(--v-theme-warning), .45); border-radius: 9px; background: rgba(var(--v-theme-warning), .07); }
.flow-webhook-secret > div { display: flex; align-items: center; justify-content: space-between; }
.flow-webhook-secret strong { display: inline-flex; gap: 5px; align-items: center; color: rgb(var(--v-theme-warning)); font-size: 10px; }
.flow-webhook-secret code { overflow: auto; padding: 5px 6px; font-size: 9px; border-radius: 5px; background: rgba(var(--v-theme-on-surface), .06); white-space: nowrap; }
.flow-webhook-secret .cx-btn { justify-content: center; font-size: 9px; }
.flow-webhook-deliveries { display: grid; gap: 6px; margin: 0 0 6px 10px; padding: 8px; border-left: 2px solid rgba(var(--v-theme-primary), .28); background: rgba(var(--v-theme-on-surface), .025); }
.flow-webhook-deliveries__head { display: flex; align-items: center; justify-content: space-between; font-size: 10px; }
.flow-webhook-deliveries__empty { padding: 8px 2px; font-size: 10px; }
.flow-webhook-delivery { display: grid; gap: 3px; padding: 7px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; }
.flow-webhook-delivery > div { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.flow-webhook-delivery small { min-width: 0; overflow: hidden; color: rgba(var(--v-theme-on-surface), .55); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.flow-webhook-delivery .flow-schedule-error { color: rgb(var(--v-theme-error)); white-space: normal; overflow-wrap: anywhere; }
.flow-history-row__main small { color: rgba(var(--v-theme-on-surface), .55); font-size: 10px; }

.flow-execution__empty { padding: 20px 4px; text-align: center; font-size: 12px; }
</style>
