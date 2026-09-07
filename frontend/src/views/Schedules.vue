<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { scheduleLabel } from '@/components/agent/scheduleLabel'
import type { CalendarSchedule, AgentScheduleSummary, WorkflowDefinition } from '@/api/types'
import { confirmAction } from '@/mf/desktop'

const { t } = useI18n()
const workflows = ref<WorkflowDefinition[]>([])
const schedules = ref<AgentScheduleSummary[]>([])
const workflowId = ref('')
const inputs = ref('{}')
const mode = ref<'DAILY' | 'WEEKLY' | 'MONTHLY' | 'INTERVAL' | 'ONCE'>('DAILY')
const clockTime = ref('09:00')
const zoneId = ref(Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC')
const weekdays = ref([1])
const monthDay = ref(1)
const interval = ref(60)
const unit = ref(60)
const isCalendar = computed(() => ['DAILY', 'WEEKLY', 'MONTHLY'].includes(mode.value))
const calendar = computed<CalendarSchedule | undefined>(() => isCalendar.value ? {
  frequency: mode.value as CalendarSchedule['frequency'], time: clockTime.value,
  zoneId: zoneId.value, weekdays: weekdays.value, monthDay: Number(monthDay.value),
} : undefined)
const preview = computed(() => scheduleLabel({ calendar: calendar.value,
  recurring: mode.value !== 'ONCE', intervalSeconds: Number(interval.value) * unit.value }, t))
const immediate = ref(false)
const loading = ref(false)
const saving = ref(false)
const deleting = ref<string | null>(null)
const error = ref('')
const success = ref('')
const published = computed(() => workflows.value.filter((workflow) => workflow.published))
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false

async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    const [definitions, active] = await Promise.all([api.workflows(), api.agentSchedules()])
    if (disposed) return
    workflows.value = definitions
    schedules.value = active
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('agent.failed')
  } finally {
    loading.value = false
  }
}

async function create() {
  if (saving.value) return
  error.value = ''
  success.value = ''
  let parsed: Record<string, unknown>
  try {
    const value: unknown = JSON.parse(inputs.value)
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error()
    parsed = value as Record<string, unknown>
  } catch {
    error.value = t('schedules.invalidInputs')
    return
  }
  const seconds = Number(interval.value) * unit.value
  if (!isCalendar.value && (!Number.isInteger(seconds) || seconds < 60 || seconds >= 604800)) {
    error.value = t('schedules.invalidInterval')
    return
  }
  if (isCalendar.value) {
    try {
      new Intl.DateTimeFormat('en', { timeZone: zoneId.value }).format()
      if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(clockTime.value)
        || (mode.value === 'WEEKLY' && !weekdays.value.length)) throw new Error()
    } catch {
      error.value = t('schedules.invalidCalendar')
      return
    }
  }
  if (!published.value.some((workflow) => workflow.id === workflowId.value)) return
  saving.value = true
  try {
    await api.agentCreateSchedule({ workflowId: workflowId.value, inputs: parsed,
      intervalSeconds: isCalendar.value ? 60 : seconds, recurring: mode.value !== 'ONCE',
      fireImmediately: immediate.value, calendar: calendar.value })
    success.value = t('schedules.created')
    await refresh()
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('agent.failed')
  } finally {
    saving.value = false
  }
}

async function remove(id: string) {
  if (deleting.value || !await confirmAction(t('schedules.deleteConfirm'))) return
  deleting.value = id
  error.value = ''
  try {
    await api.agentDeleteSchedule(id)
    schedules.value = schedules.value.filter((schedule) => schedule.scheduleId !== id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('agent.failed')
  } finally {
    deleting.value = null
  }
}

function name(id: string) {
  return workflows.value.find((workflow) => workflow.id === id)?.name ?? id
}
function time(value: string) { return new Date(value).toLocaleString() }
onMounted(() => {
  void refresh()
  timer = setInterval(() => { if (!document.hidden && !saving.value && !deleting.value) void refresh() }, 15000)
})
onBeforeUnmount(() => { disposed = true; clearInterval(timer) })
</script>

<template>
  <div class="cx-page schedules-page">
    <header>
      <h1 class="cx-page-title">{{ t('schedules.title') }}</h1>
      <p class="cx-muted">{{ t('schedules.hint') }}</p>
    </header>
    <p class="cx-muted">{{ t('schedules.runtime') }}</p>
    <div v-if="error" role="alert" class="cx-alert cx-alert--error">{{ error }}</div>
    <p v-if="success" role="status">{{ success }}</p>
    <form class="schedule-form" @submit.prevent="create">
      <label for="schedule-workflow">{{ t('schedules.workflow') }}</label>
      <select id="schedule-workflow" v-model="workflowId" class="cx-input" required :disabled="saving">
        <option disabled value="">{{ t('schedules.choose') }}</option>
        <option v-for="workflow in published" :key="workflow.id" :value="workflow.id">{{ workflow.name }}</option>
      </select>
      <p v-if="!loading && !published.length">{{ t('schedules.emptyWorkflows') }}</p>
      <RouterLink to="/flows">{{ t('schedules.flows') }}</RouterLink>
      <label for="schedule-mode">{{ t('schedules.frequency') }}</label>
      <select id="schedule-mode" v-model="mode" class="cx-input" :disabled="saving">
        <option v-for="option in ['DAILY', 'WEEKLY', 'MONTHLY', 'INTERVAL', 'ONCE']" :key="option" :value="option">{{ t(`schedules.mode${option}`) }}</option>
      </select>
      <fieldset v-if="mode === 'WEEKLY'" class="schedule-weekdays" :disabled="saving">
        <legend>{{ t('schedules.weekdays') }}</legend>
        <label v-for="day in 7" :key="day"><input v-model="weekdays" type="checkbox" :value="day"> {{ t(`schedules.weekday${day}`) }}</label>
      </fieldset>
      <template v-if="mode === 'MONTHLY'">
        <label for="schedule-month-day">{{ t('schedules.monthDay') }}</label>
        <select id="schedule-month-day" v-model.number="monthDay" class="cx-input" :disabled="saving">
          <option v-for="day in 31" :key="day" :value="day">{{ t('schedules.dayOfMonth', { day }) }}</option>
          <option :value="-1">{{ t('schedules.lastDay') }}</option>
        </select>
        <small class="cx-muted">{{ t('schedules.shortMonth') }}</small>
      </template>
      <template v-if="isCalendar">
        <label for="schedule-time">{{ t('schedules.time') }}</label>
        <input id="schedule-time" v-model="clockTime" class="cx-input" type="time" required :disabled="saving">
        <label for="schedule-zone">{{ t('schedules.zone') }}</label>
        <input id="schedule-zone" v-model.trim="zoneId" class="cx-input" list="schedule-zones" required :disabled="saving">
        <datalist id="schedule-zones">
          <option v-for="zone in ['Asia/Shanghai', 'Asia/Tokyo', 'Europe/London', 'America/New_York', 'UTC']" :key="zone" :value="zone" />
        </datalist>
      </template>
      <template v-else>
        <label for="schedule-interval">{{ t('schedules.interval') }}</label>
        <div class="schedule-interval">
          <input id="schedule-interval" v-model.number="interval" class="cx-input" type="number" min="1" :max="Math.floor(604799 / unit)" step="1" required :disabled="saving">
          <select v-model.number="unit" class="cx-input" :aria-label="t('schedules.unit')" :disabled="saving">
            <option :value="60">{{ t('schedules.minutes') }}</option>
            <option :value="3600">{{ t('schedules.hours') }}</option>
          </select>
        </div>
        <small class="cx-muted">{{ t('schedules.legacyExpiry') }}</small>
      </template>
      <p class="schedule-preview" aria-live="polite">{{ preview }}</p>
      <label><input v-model="immediate" type="checkbox" :disabled="saving"> {{ t('schedules.immediate') }}</label>
      <details>
        <summary>{{ t('schedules.advanced') }}</summary>
        <label for="schedule-inputs">{{ t('schedules.inputs') }}</label>
        <textarea id="schedule-inputs" v-model="inputs" class="cx-input" rows="4" :disabled="saving" spellcheck="false" />
      </details>
      <button class="cx-btn cx-btn--primary" type="submit" :disabled="saving || loading || !workflowId || !published.length">{{ t('schedules.create') }}</button>
    </form>
    <div class="schedule-list-head">
      <h2>{{ t('agent.schedules') }}</h2>
      <button class="cx-btn" :disabled="loading" @click="error = ''; refresh()">{{ t('schedules.refresh') }}</button>
    </div>
    <p v-if="loading && !schedules.length" role="status">{{ t('schedules.loading') }}</p>
    <p v-else-if="!schedules.length" class="cx-muted">{{ t('schedules.empty') }}</p>
    <article v-for="schedule in schedules" :key="schedule.scheduleId" class="schedule-card">
      <div>
        <RouterLink :to="`/flows/${encodeURIComponent(schedule.workflowId)}`">{{ name(schedule.workflowId) }}</RouterLink>
        <p>{{ scheduleLabel(schedule, t) }} · {{ t('agent.scheduleFires', { n: schedule.fires }) }}</p>
        <p>{{ t('agent.scheduleNext', { time: time(schedule.nextFireAt) }) }}</p>
        <p v-if="schedule.expiresAt">{{ t('schedules.expires', { time: time(schedule.expiresAt) }) }}</p>
        <p v-if="!schedule.expiresAt">{{ t('schedules.noExpiry') }}</p>
        <p v-if="schedule.missedFires">{{ t('agent.scheduleMissed', { n: schedule.missedFires }) }}</p>
        <p v-if="schedule.lastError" class="schedule-error">{{ schedule.lastError }}</p>
      </div>
      <button class="cx-iconbtn" :disabled="!!deleting || loading" :aria-label="t('agent.deleteSchedule')" :title="t('agent.deleteSchedule')" @click="remove(schedule.scheduleId)"><i class="mdi mdi-delete-outline" /></button>
    </article>
  </div>
</template>

<style scoped>
.schedules-page { max-width: 960px; margin: 0 auto; }
.schedule-form { display: grid; gap: 12px; max-width: 600px; margin: 24px 0; }
.schedule-weekdays { display: flex; flex-wrap: wrap; gap: 12px; padding: 12px; border: 1px solid var(--cx-border); }
.schedule-interval { display: flex; gap: 12px; }
.schedule-preview { padding: 12px; border: 1px solid var(--cx-border); border-radius: 8px; }
.schedule-form details > label { display: block; margin: 12px 0; }
.schedule-form textarea { font-family: monospace; resize: vertical; }
.schedule-form .cx-btn { justify-self: start; }
.schedule-list-head, .schedule-card { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.schedule-card { padding: 16px 0; border-bottom: 1px solid var(--cx-border); align-items: flex-start; }
.schedule-card p { margin: 6px 0; overflow-wrap: anywhere; }
.schedule-card > div { min-width: 0; }
.schedule-error { color: rgb(var(--v-theme-error)); }
</style>
