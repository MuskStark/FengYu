<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { actionable, invoke } from '../sdk'

interface SendTask { confirmationId: string; accountId: number; mode: string; status: string; expiresAt: string; updatedAt: string }
interface SentMessage { id: number; confirmationId?: string; accountEmail: string; subject?: string; status: string; errorMessage?: string; sentAt: string }
const { t } = useI18n()
const query = ref(''), status = ref<string>(), offset = ref(0), limit = 25
const tasks = ref<SendTask[]>([]), messages = ref<SentMessage[]>([]), error = ref(''), busy = ref(false)
async function load(): Promise<void> {
  busy.value = true; error.value = ''
  try {
    const result = await invoke<{ tasks: SendTask[]; messages: SentMessage[] }>('email_send_records_query', {
      query: query.value, taskStatus: status.value, offset: offset.value, limit,
    })
    tasks.value = result.tasks ?? []; messages.value = result.messages ?? []
  } catch (value) { error.value = actionable(value, t('records.loadAction')) }
  finally { busy.value = false }
}
function search(): void { offset.value = 0; void load() }
function previous(): void { offset.value = Math.max(0, offset.value - limit); void load() }
function next(): void { offset.value += limit; void load() }
onMounted(load)
</script>

<template>
  <v-card class="surface" variant="flat">
    <v-card-title>{{ t('records.title') }}</v-card-title>
    <v-card-text>
      <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
      <div class="inline-fields">
        <v-text-field v-model="query" data-testid="record-search" :label="t('records.search')" @keyup.enter="search" />
        <v-select v-model="status" :items="['PENDING','SENDING','COMPLETED','PARTIAL_FAILED','FAILED','REJECTED','EXPIRED']" clearable :label="t('records.status')" />
        <v-btn data-testid="record-search-submit" :loading="busy" @click="search">{{ t('common.search') }}</v-btn>
      </div>
      <h3>{{ t('records.tasks') }}</h3>
      <v-table><thead><tr><th>{{ t('records.confirmationId') }}</th><th>{{ t('records.mode') }}</th><th>{{ t('records.status') }}</th><th>{{ t('records.updated') }}</th></tr></thead>
        <tbody><tr v-for="task in tasks" :key="task.confirmationId"><td>{{ task.confirmationId }}</td><td>{{ task.mode }}</td><td><v-chip size="small">{{ task.status }}</v-chip></td><td>{{ task.updatedAt }}</td></tr></tbody></v-table>
      <h3 class="mt-6">{{ t('records.messages') }}</h3>
      <v-table><thead><tr><th>{{ t('compose.subject') }}</th><th>{{ t('records.account') }}</th><th>{{ t('records.status') }}</th><th>{{ t('records.error') }}</th><th>{{ t('records.sentAt') }}</th></tr></thead>
        <tbody><tr v-for="message in messages" :key="message.id"><td>{{ message.subject || t('compose.noSubject') }}</td><td>{{ message.accountEmail }}</td><td><v-chip size="small">{{ message.status }}</v-chip></td><td>{{ message.errorMessage || t('common.none') }}</td><td>{{ message.sentAt }}</td></tr></tbody></v-table>
      <div class="pager"><v-btn :disabled="offset === 0" @click="previous">{{ t('common.previous') }}</v-btn><span>{{ t('common.page', { page: Math.floor(offset / limit) + 1 }) }}</span><v-btn @click="next">{{ t('common.next') }}</v-btn></div>
    </v-card-text>
  </v-card>
</template>
