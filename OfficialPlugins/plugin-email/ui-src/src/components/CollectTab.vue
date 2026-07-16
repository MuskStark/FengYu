<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FileRef } from '@infinia/plugin-sdk'
import { useAccountsStore } from '../stores/accounts'
import { useArchiveStore } from '../stores/archive'
import { actionable, files, invoke } from '../sdk'

const { t } = useI18n(), accounts = useAccountsStore(), archive = useArchiveStore()
const folder = ref('INBOX'), start = ref(''), end = ref(''), output = ref<FileRef | null>(null)
const busy = ref(false), error = ref(''), summary = ref(''), detail = ref<Record<string, unknown>>()
async function choose() { try { output.value = await files.outputDirectory() } catch (value) { error.value = actionable(value, t('archive.selectOutput')) } }
const instant = (value: string) => value ? new Date(value).toISOString() : undefined
async function collect() {
  busy.value = true
  try {
    const result = await invoke<{ collection: { newArchived: number; skippedDuplicates: number; failures: number } }>('email_archive_fetch', { accountId: accounts.selectedId, folder: folder.value, start: instant(start.value), end: instant(end.value), outputDirectory: output.value })
    summary.value = result.summary
    archive.updateProgress({ processed: result.collection.newArchived + result.collection.skippedDuplicates + result.collection.failures, newArchived: result.collection.newArchived, duplicates: result.collection.skippedDuplicates, failed: result.collection.failures, successful: result.collection.newArchived })
    await loadResults()
  } catch (value) { error.value = actionable(value, t('archive.collectAction')) }
  finally { busy.value = false }
}
async function loadResults() {
  try {
    const result = await invoke<{ messages: Record<string, unknown>[] }>('email_archive_query', { accountId: accounts.selectedId, folder: folder.value, offset: archive.offset, limit: archive.limit })
    archive.messages = result.messages ?? []
  } catch (value) { error.value = actionable(value, t('archive.loadAction')) }
}
async function openDetail(id: unknown) {
  try { const result = await invoke<{ message: Record<string, unknown> }>('email_archive_detail', { id }); detail.value = result.message }
  catch (value) { error.value = actionable(value, t('archive.detailAction')) }
}
function previous() { archive.previousPage(); void loadResults() }
function next() { archive.nextPage(); void loadResults() }
onMounted(loadResults)
</script>

<template>
  <section class="archive-workspace">
    <v-card class="surface" variant="flat"><v-card-title>{{ t('archive.title') }}</v-card-title><v-card-text>
      <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert><v-alert v-if="summary" type="success" class="mb-4">{{ summary }}</v-alert>
      <div class="form-grid"><v-select v-model="accounts.selectedId" :items="accounts.accounts" item-title="email" item-value="id" :label="t('archive.account')" /><v-text-field v-model="folder" :label="t('archive.folder')" /><v-text-field v-model="start" type="datetime-local" :label="t('archive.from')" /><v-text-field v-model="end" type="datetime-local" :label="t('archive.to')" /></div>
      <div class="d-flex ga-2"><v-btn variant="tonal" @click="choose">{{ output?.name || t('archive.output') }}</v-btn><v-btn color="primary" :loading="busy" :disabled="!output || !accounts.selectedId" @click="collect">{{ t('archive.collect') }}</v-btn></div>
      <div data-testid="archive-progress" class="progress-grid"><span>{{ t('archive.processed') }} {{ archive.progress.processed }}</span><span>{{ t('archive.new') }} {{ archive.progress.newArchived }}</span><span>{{ t('archive.duplicates') }} {{ archive.progress.duplicates }}</span><span>{{ t('archive.failed') }} {{ archive.progress.failed }}</span></div>
    </v-card-text></v-card>
    <v-card data-testid="archive-results" class="surface mt-4" variant="flat"><v-card-title>{{ t('archive.results') }}</v-card-title><v-card-text><v-table><thead><tr><th>{{ t('compose.subject') }}</th><th>{{ t('archive.sender') }}</th><th>{{ t('archive.folder') }}</th><th>{{ t('archive.archivedAt') }}</th></tr></thead><tbody><tr v-for="message in archive.messages" :key="String(message.id)" @click="openDetail(message.id)"><td>{{ message.subject }}</td><td>{{ message.fromAddress }}</td><td>{{ message.folder }}</td><td>{{ message.archivedAt }}</td></tr></tbody></v-table><div class="pager"><v-btn :disabled="archive.offset === 0" @click="previous">{{ t('common.previous') }}</v-btn><span>{{ t('common.page', { page: Math.floor(archive.offset / archive.limit) + 1 }) }}</span><v-btn data-testid="archive-next-page" @click="next">{{ t('common.next') }}</v-btn></div>
      <v-sheet v-if="detail" class="detail pa-4 mt-4" rounded><dl><template v-for="(value, key) in detail" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl></v-sheet>
    </v-card-text></v-card>
  </section>
</template>
