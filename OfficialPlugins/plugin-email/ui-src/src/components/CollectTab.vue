<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FileRef } from '@infinia/plugin-sdk'
import { useAccountsStore } from '../stores/accounts'
import { useArchiveStore } from '../stores/archive'
import { actionable, files, invoke } from '../sdk'

const { t } = useI18n(), accounts = useAccountsStore(), archive = useArchiveStore()
const folder = ref('INBOX'), start = ref(''), end = ref(''), output = ref<FileRef | null>(null)
const busy = ref(false), error = ref(''), summary = ref(''), detail = ref<Record<string, unknown>>()
const folders = ref<string[]>(['INBOX']), foldersLoading = ref(false)
const jobId = ref(''), pollTimer = ref<number>()
async function choose() { try { output.value = await files.outputDirectory() } catch (value) { error.value = actionable(value, t('archive.selectOutput')) } }
// Date inputs yield yyyy-mm-dd. Expand to full-day bounds so filtering by a date includes every
// message that arrived that day: start = 00:00:00.000, end = 23:59:59.999 (local, then toISOString).
const dayStart = (value: string) => value ? new Date(value + 'T00:00:00').toISOString() : undefined
const dayEnd = (value: string) => value ? new Date(value + 'T23:59:59.999').toISOString() : undefined
async function loadFolders() {
  if (!accounts.selectedId) { folders.value = ['INBOX']; return }
  foldersLoading.value = true
  try {
    const result = await invoke<{ folders: string[] }>('email_imap_folders', { accountId: accounts.selectedId })
    folders.value = result.folders?.length ? result.folders : ['INBOX']
    if (!folders.value.includes(folder.value)) folder.value = 'INBOX'
  } catch (value) {
    folders.value = ['INBOX']
    error.value = actionable(value, t('archive.loadFolders'))
  } finally { foldersLoading.value = false }
}
// Parse the latest progress log line ("42/1974 new=10 skipped=2 failed=0") emitted by the backend's
// ProgressSink into the live counters the progress grid displays.
function applyProgressLine(line: string) {
  const match = line.match(/(\d+)\/(\d+)\s+new=(\d+)\s+skipped=(\d+)\s+failed=(\d+)/)
  if (!match) return
  archive.updateProgress({
    processed: Number(match[1]), newArchived: Number(match[3]),
    duplicates: Number(match[4]), failed: Number(match[5]), successful: Number(match[3]),
  })
}
function stopPolling() { if (pollTimer.value) { window.clearInterval(pollTimer.value); pollTimer.value = undefined } }
// Archive runs as a background job (email_archive_fetch_start → poll email_archive_fetch_status)
// so a folder with thousands of messages does not exceed the host's per-RPC timeout. Each status
// poll drains new progress lines and, on completion, carries the final CollectResult.
async function collect() {
  busy.value = true; error.value = ''; summary.value = ''
  archive.updateProgress({ processed: 0, newArchived: 0, duplicates: 0, failed: 0, successful: 0 })
  try {
    const started = await invoke<{ jobId: string }>('email_archive_fetch_start',
      { accountId: accounts.selectedId, folder: folder.value, start: dayStart(start.value), end: dayEnd(end.value), outputDirectory: output.value })
    jobId.value = started.jobId
    let cursor = 0
    pollTimer.value = window.setInterval(async () => {
      try {
        const snap = await invoke<{ done: boolean; logs: string[]; cursor: number; status: string; result?: { newArchived: number; skippedDuplicates: number; failures: number }; error?: string }>(
          'email_archive_fetch_status', { jobId: jobId.value, cursor })
        for (const line of snap.logs ?? []) applyProgressLine(line)
        cursor = snap.cursor
        if (snap.done) {
          stopPolling(); jobId.value = ''; busy.value = false
          if (snap.status === 'FAILED') { error.value = snap.error ?? t('archive.collectAction') }
          else if (snap.status === 'CANCELLED') { summary.value = t('archive.cancelled') }
          else if (snap.result) {
            summary.value = t('archive.collectSummary', { archived: snap.result.newArchived, skipped: snap.result.skippedDuplicates, failed: snap.result.failures })
            archive.updateProgress({ successful: snap.result.newArchived })
          }
          await loadResults()
        }
      } catch (value) { stopPolling(); jobId.value = ''; busy.value = false; error.value = actionable(value, t('archive.collectAction')) }
    }, 1000)
  } catch (value) { busy.value = false; error.value = actionable(value, t('archive.collectAction')) }
}
async function cancelCollect() {
  if (!jobId.value) return
  try { await invoke('email_archive_fetch_cancel', { jobId: jobId.value }) }
  catch (value) { error.value = actionable(value, t('archive.collectAction')) }
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
// App.vue loads accounts once on mount; if that failed, the account dropdown here would stay empty
// with no retry. Re-fetch on mount when the list is empty so the picker is always populated.
onMounted(() => { if (!accounts.accounts.length) accounts.load().catch(value => { error.value = actionable(value, t('accounts.loading')) }) })
onMounted(loadFolders)
// The folder list is per-account: reload whenever the selected account changes.
watch(() => accounts.selectedId, () => { void loadFolders() })
onMounted(loadResults)
// Stop the status poll loop if the user navigates away mid-archive.
onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="archive-workspace">
    <v-card class="surface" variant="flat"><v-card-title>{{ t('archive.title') }}</v-card-title><v-card-text>
      <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert><v-alert v-if="summary" type="success" class="mb-4">{{ summary }}</v-alert>
      <div class="form-grid"><v-select v-model="accounts.selectedId" :items="accounts.accounts" item-title="email" item-value="id" :label="t('archive.account')" /><v-select v-model="folder" :items="folders" :loading="foldersLoading" :label="t('archive.folder')" /><v-text-field v-model="start" type="date" :label="t('archive.from')" /><v-text-field v-model="end" type="date" :label="t('archive.to')" /></div>
      <div class="d-flex ga-2"><v-btn variant="tonal" @click="choose">{{ output?.name || t('archive.output') }}</v-btn><v-btn color="primary" :loading="busy" :disabled="!output || !accounts.selectedId || !!jobId" @click="collect">{{ t('archive.collect') }}</v-btn><v-btn v-if="jobId" color="error" variant="tonal" @click="cancelCollect">{{ t('archive.cancel') }}</v-btn></div>
      <v-progress-linear v-if="jobId" indeterminate class="mt-2" />
      <div data-testid="archive-progress" class="progress-grid"><span>{{ t('archive.processed') }} {{ archive.progress.processed }}</span><span>{{ t('archive.new') }} {{ archive.progress.newArchived }}</span><span>{{ t('archive.duplicates') }} {{ archive.progress.duplicates }}</span><span>{{ t('archive.failed') }} {{ archive.progress.failed }}</span></div>
    </v-card-text></v-card>
    <v-card data-testid="archive-results" class="surface mt-4" variant="flat"><v-card-title>{{ t('archive.results') }}</v-card-title><v-card-text><v-table><thead><tr><th>{{ t('compose.subject') }}</th><th>{{ t('archive.sender') }}</th><th>{{ t('archive.folder') }}</th><th>{{ t('archive.archivedAt') }}</th></tr></thead><tbody><tr v-for="message in archive.messages" :key="String(message.id)" @click="openDetail(message.id)"><td>{{ message.subject }}</td><td>{{ message.fromAddress }}</td><td>{{ message.folder }}</td><td>{{ message.archivedAt }}</td></tr></tbody></v-table><div class="pager"><v-btn :disabled="archive.offset === 0" @click="previous">{{ t('common.previous') }}</v-btn><span>{{ t('common.page', { page: Math.floor(archive.offset / archive.limit) + 1 }) }}</span><v-btn data-testid="archive-next-page" @click="next">{{ t('common.next') }}</v-btn></div>
      <v-sheet v-if="detail" class="detail pa-4 mt-4" rounded><dl><template v-for="(value, key) in detail" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl></v-sheet>
    </v-card-text></v-card>
  </section>
</template>
