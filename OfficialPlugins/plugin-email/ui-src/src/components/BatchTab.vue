<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mdiCloseCircle, mdiPaperclip } from '@mdi/js'
import { FyIcon } from '@infinia/plugin-ui'
import { useAccountsStore } from '../stores/accounts'
import { useBatchStore } from '../stores/batch'
import { useContactsStore } from '../stores/contacts'
import { actionable, checked, files, rpc } from '../sdk'
import RichTextEditor from './RichTextEditor.vue'
import ConfirmationDialog from './ConfirmationDialog.vue'

const { t } = useI18n()
const accounts = useAccountsStore(), batch = useBatchStore(), contacts = useContactsStore()
const busy = ref(false), previewBusy = ref(false), error = ref(''), dialog = ref(false)
let previewTimer: number | undefined
const canPreview = computed(() => Boolean(accounts.selectedId && batch.inputDirectory
  && batch.recipientGroupTagIds.length && (batch.plainText.trim() || batch.htmlText.trim())))
const params = () => ({
  accountId: accounts.selectedId!,
  recipientGroupTagIds: batch.recipientGroupTagIds,
  ccGroupTagIds: batch.ccGroupTagIds,
  inputDirectory: batch.inputDirectory as unknown as string,
  commonAttachments: batch.commonAttachments as unknown as string[],
  subject: batch.subject,
  plainText: batch.plainText,
  htmlText: batch.htmlText,
})

onMounted(() => {
  if (!contacts.tags.length) contacts.load().catch(value => { error.value = actionable(value, t('batch.loadTags')) })
})
watch(() => [accounts.selectedId, batch.inputDirectory, batch.recipientGroupTagIds, batch.ccGroupTagIds,
  batch.commonAttachments, batch.subject, batch.plainText, batch.htmlText], () => {
  window.clearTimeout(previewTimer)
  batch.clearPreview()
  if (canPreview.value) previewTimer = window.setTimeout(refreshPreview, 450)
}, { deep: true })
onBeforeUnmount(() => window.clearTimeout(previewTimer))

async function chooseDirectory(): Promise<void> {
  try { batch.inputDirectory = await files.inputDirectory() }
  catch (value) { error.value = actionable(value, t('batch.selectDirectory')) }
}
async function addCommonAttachment(): Promise<void> {
  try { const value = await files.open(); if (value) batch.commonAttachments.push(value) }
  catch (value) { error.value = actionable(value, t('batch.selectCommon')) }
}
function removeCommonAttachment(id: string): void {
  batch.commonAttachments = batch.commonAttachments.filter(item => item.id !== id)
}
async function refreshPreview(): Promise<void> {
  if (!canPreview.value) return
  previewBusy.value = true; error.value = ''
  try {
    const result = await checked(rpc.email_batch_preview(params()))
    batch.applyPreview(result.preview)
  } catch (value) { error.value = actionable(value, t('batch.previewAction')) }
  finally { previewBusy.value = false }
}
async function prepare(): Promise<void> {
  busy.value = true; error.value = ''
  try {
    const result = await checked(rpc.email_send_batch(params()))
    batch.confirmation = result.confirmation; dialog.value = true
  } catch (value) { error.value = actionable(value, t('batch.prepareAction')) }
  finally { busy.value = false }
}
async function confirm(): Promise<void> {
  if (!batch.confirmation) return
  busy.value = true
  try {
    const result = await checked(rpc.confirm_send({
      confirmationId: batch.confirmation.confirmationId,
    }))
    batch.sendResult = result.send; dialog.value = false
  } catch (value) { error.value = actionable(value, t('batch.sendAction')) }
  finally { busy.value = false }
}
async function reject(): Promise<void> {
  if (!batch.confirmation) return
  try { await checked(rpc.reject_send({ confirmationId: batch.confirmation.confirmationId })); dialog.value = false }
  catch (value) { error.value = actionable(value, t('batch.cancelAction')) }
}
</script>

<template>
  <section class="batch-workspace">
    <v-card class="surface" variant="flat">
      <v-card-title>{{ t('batch.title') }}</v-card-title>
      <v-card-text>
        <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
        <v-alert v-if="batch.sendResult" :type="batch.sendResult.failed ? 'warning' : 'success'" class="mb-4">
          {{ t('compose.sendResult', { sent: batch.sendResult.succeeded, failed: batch.sendResult.failed }) }}
        </v-alert>
        <div class="form-grid">
          <v-select v-model="accounts.selectedId" :items="accounts.accounts" item-title="email" item-value="id" :label="t('compose.from')" />
          <div>
            <v-btn data-testid="batch-directory" variant="tonal" @click="chooseDirectory">{{ t('batch.directory') }}</v-btn>
            <p class="hint">{{ batch.inputDirectory?.name ?? t('batch.noDirectory') }}</p>
          </div>
          <v-select v-model="batch.recipientGroupTagIds" :items="contacts.tags" item-title="name" item-value="id" multiple chips :label="t('batch.recipientGroups')" />
          <v-select v-model="batch.ccGroupTagIds" :items="contacts.tags" item-title="name" item-value="id" multiple chips :label="t('batch.ccGroups')" />
        </div>
        <v-alert type="info" variant="tonal" class="mb-4">{{ t('batch.formula') }}</v-alert>
        <v-text-field v-model="batch.subject" :label="t('compose.subject')" />
        <RichTextEditor v-model="batch.htmlText" @update:plain-text="batch.plainText = $event" />
        <div class="attachment-row">
          <v-chip v-for="item in batch.commonAttachments" :key="item.id" closable @click:close="removeCommonAttachment(item.id)">
            <template #prepend><FyIcon :path="mdiPaperclip" :size="16" /></template>
            <template #close><FyIcon :path="mdiCloseCircle" :size="18" /></template>
            {{ item.name }}
          </v-chip>
          <v-btn data-testid="batch-common-attachment" variant="tonal" @click="addCommonAttachment">{{ t('batch.commonAttachments') }}</v-btn>
        </div>
      </v-card-text>
      <v-card-actions>
        <v-btn data-testid="batch-refresh" variant="text" :loading="previewBusy" :disabled="!canPreview" @click="refreshPreview">{{ t('batch.refresh') }}</v-btn>
        <v-spacer />
        <v-btn data-testid="batch-review" color="primary" :loading="busy" :disabled="!batch.messageCount" @click="prepare">{{ t('batch.review', { count: batch.messageCount }) }}</v-btn>
      </v-card-actions>
    </v-card>

    <v-card class="surface mt-4" variant="flat">
      <v-card-title>{{ t('batch.preview') }}</v-card-title>
      <v-card-text>
        <v-progress-linear v-if="previewBusy" indeterminate />
        <div v-if="batch.preview.messages.length" class="batch-preview-list">
          <article v-for="(message, index) in batch.preview.messages" :key="index" class="batch-preview-item">
            <h3>{{ message.attachmentTag }}</h3>
            <p><strong>{{ t('compose.to') }}:</strong> {{ message.to.join(', ') }}</p>
            <p><strong>{{ t('compose.cc') }}:</strong> {{ message.cc.join(', ') || t('common.none') }}</p>
            <p><strong>{{ t('batch.tagAttachments') }}:</strong> {{ message.tagAttachments.join(', ') }}</p>
            <p><strong>{{ t('batch.commonAttachments') }}:</strong> {{ message.commonAttachments.join(', ') || t('common.none') }}</p>
          </article>
        </div>
        <p v-else class="hint">{{ t('batch.previewEmpty') }}</p>
        <v-alert v-if="batch.preview.ignoredFiles.length" type="info" variant="tonal" class="mt-4">
          {{ t('batch.ignoredFiles') }}: {{ batch.preview.ignoredFiles.join(', ') }}
        </v-alert>
        <v-alert v-if="batch.preview.skippedTags.length" type="warning" variant="tonal" class="mt-4">
          {{ t('batch.skippedTags') }}: {{ batch.preview.skippedTags.map(item => `${item.attachmentTag} (${item.reason})`).join(', ') }}
        </v-alert>
      </v-card-text>
    </v-card>
  </section>
  <ConfirmationDialog v-model="dialog" :confirmation="batch.confirmation" :busy="busy" @approve="confirm" @reject="reject" />
</template>
