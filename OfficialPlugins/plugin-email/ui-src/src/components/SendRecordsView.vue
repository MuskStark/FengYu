<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { actionable, checked, rpc } from '../sdk'

interface SendTask { confirmationId: string; accountId: number; mode: string; status: string; expiresAt?: string; updatedAt?: string }
interface SentMessage { id: number; confirmationId?: string; accountEmail: string; subject?: string; status: string; errorMessage?: string; sentAt?: string; recipientsJson?: string }

const TASK_STATUSES = ['PENDING', 'SENDING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'REJECTED', 'EXPIRED']
const TASK_STATUS_KEYS: Record<string, string> = {
  PENDING: 'records.statusPending',
  SENDING: 'records.statusSending',
  COMPLETED: 'records.statusCompleted',
  PARTIAL_FAILED: 'records.partial',
  FAILED: 'records.statusFailed',
  REJECTED: 'records.statusRejected',
  EXPIRED: 'records.statusExpired',
}
const RECIPIENT_PREVIEW = 3

const { t } = useI18n()
const query = ref(''), status = ref<string>(), offset = ref(0), limit = 25
const tasks = ref<SendTask[]>([]), messages = ref<SentMessage[]>([]), error = ref(''), busy = ref(false)
const expanded = ref<string>()
const taskMessages = ref<Record<string, SentMessage[]>>({})
const taskErrors = ref<Record<string, string>>({})
const detailBusy = ref<string>()

async function load(): Promise<void> {
  busy.value = true; error.value = ''
  try {
    const result = await checked(rpc.email_send_records_query({
      query: query.value, taskStatus: status.value, offset: offset.value, limit,
    }))
    tasks.value = result.tasks ?? []; messages.value = result.messages ?? []
  } catch (value) { error.value = actionable(value, t('records.loadAction')) }
  finally { busy.value = false }
}
function search(): void { offset.value = 0; void load() }
function previous(): void { offset.value = Math.max(0, offset.value - limit); void load() }
function next(): void { offset.value += limit; void load() }
onMounted(load)

async function toggle(task: SendTask): Promise<void> {
  if (expanded.value === task.confirmationId) { expanded.value = undefined; return }
  expanded.value = task.confirmationId
  if (taskMessages.value[task.confirmationId] || taskErrors.value[task.confirmationId]) return
  detailBusy.value = task.confirmationId
  try {
    const result = await checked(rpc.email_send_records_query({ confirmationId: task.confirmationId, limit: 100 }))
    taskMessages.value = { ...taskMessages.value, [task.confirmationId]: result.messages ?? [] }
  } catch (value) {
    taskErrors.value = { ...taskErrors.value, [task.confirmationId]: actionable(value, t('records.detailAction')) }
  } finally { detailBusy.value = undefined }
}

const recipientCache = new Map<number, string[]>()
function recipientsOf(message: SentMessage): string[] {
  const cached = recipientCache.get(message.id)
  if (cached) return cached
  let value: string[] = []
  try {
    const parsed = JSON.parse(message.recipientsJson || '{}') as { to?: string[]; cc?: string[]; bcc?: string[] }
    value = [...(parsed.to ?? []), ...(parsed.cc ?? []), ...(parsed.bcc ?? [])]
  } catch { value = [] }
  recipientCache.set(message.id, value)
  return value
}

function taskStatusLabel(value: string): string { return TASK_STATUS_KEYS[value] ? t(TASK_STATUS_KEYS[value]) : value }
function taskStatusTone(value: string): string {
  if (value === 'COMPLETED') return 'ok'
  if (value === 'SENDING' || value === 'PARTIAL_FAILED') return 'warn'
  if (value === 'FAILED') return 'err'
  return 'neutral'
}
function messageStatusLabel(value: string): string {
  if (value === 'SUCCESS') return t('records.statusSuccess')
  if (value === 'FAILED') return t('records.statusFailed')
  return value
}
function messageStatusTone(value: string): string {
  if (value === 'SUCCESS') return 'ok'
  if (value === 'FAILED') return 'err'
  return 'neutral'
}
function modeLabel(value: string): string {
  const key = `conf.mode_${value}`
  const label = t(key)
  return label === key ? value : label
}
function formatTime(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

const statusItems = computed(() => [
  { title: t('records.allStatus'), value: undefined },
  ...TASK_STATUSES.map(value => ({ title: taskStatusLabel(value), value })),
])
</script>

<template>
  <div class="records-view">
    <div class="records-head">
      <h2 class="records-title">{{ t('records.title') }}</h2>
      <p class="records-sub">{{ t('records.subtitle', { tasks: tasks.length, messages: messages.length }) }}</p>
    </div>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>

    <v-card class="fy-surface" variant="flat">
      <div class="records-card-head">
        <span class="records-card-title">{{ t('records.tasks') }}</span>
        <div class="records-filters">
          <v-text-field v-model="query" data-testid="record-search" density="compact" hide-details
            :placeholder="t('records.search')" @keyup.enter="search" />
          <v-select v-model="status" data-testid="record-status" :items="statusItems" density="compact"
            hide-details clearable :placeholder="t('records.status')" />
          <v-btn data-testid="record-search-submit" :loading="busy" @click="search">{{ t('common.search') }}</v-btn>
        </div>
      </div>
      <v-table>
        <thead><tr>
          <th class="records-col-expand" aria-hidden="true"></th>
          <th>{{ t('records.confirmationId') }}</th><th>{{ t('records.mode') }}</th>
          <th>{{ t('records.status') }}</th><th>{{ t('records.updated') }}</th>
        </tr></thead>
        <tbody>
          <template v-for="task in tasks" :key="task.confirmationId">
            <tr class="records-task-row" @click="toggle(task)">
              <td class="records-col-expand">
                <button type="button" class="records-toggle" data-testid="task-toggle"
                  :aria-expanded="expanded === task.confirmationId" :aria-label="t('records.toggleDetail')"
                  @click.stop="toggle(task)">
                  <svg :class="['records-chevron', { 'records-chevron--open': expanded === task.confirmationId }]" viewBox="0 0 24 24"><path d="M9 18l6-6-6-6" /></svg>
                </button>
              </td>
              <td class="records-mono">{{ task.confirmationId }}</td>
              <td><span class="mode-chip">{{ modeLabel(task.mode) }}</span></td>
              <td><span :class="['status-chip', `status-chip--${taskStatusTone(task.status)}`]">{{ taskStatusLabel(task.status) }}</span></td>
              <td class="records-muted">{{ formatTime(task.updatedAt) }}</td>
            </tr>
            <tr v-if="expanded === task.confirmationId" class="records-detail-row">
              <td colspan="5" class="records-detail-cell">
                <p v-if="detailBusy === task.confirmationId" class="records-detail-status">{{ t('records.loadingDetail') }}</p>
                <p v-else-if="taskErrors[task.confirmationId]" class="records-detail-status records-detail-error">{{ taskErrors[task.confirmationId] }}</p>
                <template v-else-if="(taskMessages[task.confirmationId] ?? []).length">
                  <div v-for="message in taskMessages[task.confirmationId]" :key="message.id" class="records-message">
                    <div class="records-message-head">
                      <span class="records-message-subject">{{ message.subject || t('compose.noSubject') }}</span>
                      <span :class="['status-chip', `status-chip--${messageStatusTone(message.status)}`]">{{ messageStatusLabel(message.status) }}</span>
                      <span class="records-message-meta">{{ message.accountEmail }}<template v-if="message.sentAt"> · {{ formatTime(message.sentAt) }}</template></span>
                    </div>
                    <div class="records-recipients">
                      <span class="records-recipients-label">{{ t('records.recipients') }}</span>
                      <span v-for="recipient in recipientsOf(message).slice(0, RECIPIENT_PREVIEW)" :key="recipient" class="records-recipient">{{ recipient }}</span>
                      <span v-if="recipientsOf(message).length > RECIPIENT_PREVIEW" class="records-recipient records-recipient--more">{{ t('contacts.tagsMore', { count: recipientsOf(message).length - RECIPIENT_PREVIEW }) }}</span>
                      <span v-if="!recipientsOf(message).length" class="records-muted">{{ t('common.none') }}</span>
                    </div>
                    <p v-if="message.errorMessage" class="records-message-error">{{ message.errorMessage }}</p>
                  </div>
                </template>
                <p v-else class="records-detail-status">{{ t('records.noMessages') }}</p>
              </td>
            </tr>
          </template>
          <tr v-if="!tasks.length"><td colspan="5" class="records-empty">{{ t('records.emptyTasks') }}</td></tr>
        </tbody>
      </v-table>
    </v-card>

    <v-card class="fy-surface records-card-gap" variant="flat">
      <div class="records-card-head"><span class="records-card-title">{{ t('records.messages') }}</span></div>
      <v-table>
        <thead><tr>
          <th>{{ t('compose.subject') }}</th><th>{{ t('records.account') }}</th>
          <th>{{ t('records.status') }}</th><th>{{ t('records.error') }}</th><th>{{ t('records.sentAt') }}</th>
        </tr></thead>
        <tbody>
          <tr v-for="message in messages" :key="message.id">
            <td>{{ message.subject || t('compose.noSubject') }}</td>
            <td class="records-muted">{{ message.accountEmail }}</td>
            <td><span :class="['status-chip', `status-chip--${messageStatusTone(message.status)}`]">{{ messageStatusLabel(message.status) }}</span></td>
            <td><span v-if="message.errorMessage" class="records-message-error">{{ message.errorMessage }}</span><span v-else class="records-muted">{{ t('common.none') }}</span></td>
            <td class="records-muted">{{ formatTime(message.sentAt) }}</td>
          </tr>
          <tr v-if="!messages.length"><td colspan="5" class="records-empty">{{ t('records.emptyMessages') }}</td></tr>
        </tbody>
      </v-table>
    </v-card>

    <div class="pager">
      <v-btn :disabled="offset === 0" @click="previous">{{ t('common.previous') }}</v-btn>
      <span>{{ t('common.page', { page: Math.floor(offset / limit) + 1 }) }}</span>
      <v-btn @click="next">{{ t('common.next') }}</v-btn>
    </div>
  </div>
</template>
