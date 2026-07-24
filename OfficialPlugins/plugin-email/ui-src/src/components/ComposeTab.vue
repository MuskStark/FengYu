<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mdiCloseCircle, mdiPaperclip } from '@mdi/js'
import { FyIcon } from '@infinia/plugin-ui'
import { useAccountsStore } from '../stores/accounts'
import { useComposeStore, type ComposeMode } from '../stores/compose'
import { useContactsStore } from '../stores/contacts'
import { actionable, files, invoke } from '../sdk'
import RichTextEditor from './RichTextEditor.vue'
import ConfirmationDialog from './ConfirmationDialog.vue'

const { t } = useI18n()
const accounts = useAccountsStore()
const compose = useComposeStore()
const contacts = useContactsStore()
const busy = ref(false), error = ref(''), dialog = ref(false)
let draftTimer: number | undefined
compose.restoreDraft()

const canReview = computed(() => Boolean(accounts.selectedId)
  && (compose.mode === 'DIRECT' ? compose.normalizedTo.length > 0 : compose.recipientTagIds.length > 0)
  && Boolean(compose.plainText.trim() || compose.htmlText.trim()))
const recipientHint = computed(() => compose.mode === 'CONTACT_TAGS'
  ? t('compose.separateMessages', { count: contacts.recipientPreview.length })
  : t('compose.directHint', { count: compose.normalizedTo.length }))

watch(() => [compose.mode, compose.recipientTagIds, compose.to, compose.cc, compose.subject,
  compose.htmlText, compose.plainText], () => {
  window.clearTimeout(draftTimer)
  draftTimer = window.setTimeout(compose.persistDraft, 400)
}, { deep: true })
onBeforeUnmount(() => window.clearTimeout(draftTimer))

async function selectMode(mode: ComposeMode): Promise<void> {
  compose.mode = mode
  if (mode === 'CONTACT_TAGS' && !contacts.tags.length) {
    try { await contacts.load() } catch (value) { error.value = actionable(value, t('compose.loadTags')) }
  }
}
async function addAttachment(): Promise<void> {
  try { const value = await files.open(); if (value) compose.attachments.push(value) }
  catch (value) { error.value = actionable(value, t('compose.selectAttachment')) }
}
function removeAttachment(id: string): void {
  compose.attachments = compose.attachments.filter(item => item.id !== id)
}
async function prepare(): Promise<void> {
  busy.value = true; error.value = ''; compose.clearTransient()
  try {
    const recipients = compose.mode === 'DIRECT'
      ? { to: compose.normalizedTo }
      : { recipientTagIds: compose.recipientTagIds }
    const result = await invoke<{ confirmation: NonNullable<typeof compose.confirmation> }>('email_send_single', {
      accountId: accounts.selectedId,
      ...recipients,
      cc: compose.normalizedCc,
      subject: compose.subject,
      plainText: compose.plainText,
      htmlText: compose.htmlText,
      attachments: compose.attachments,
    })
    compose.setConfirmation(result.confirmation)
    dialog.value = true
  } catch (value) { error.value = actionable(value, t('compose.prepareAction')) }
  finally { busy.value = false }
}
async function confirm(): Promise<void> {
  if (!compose.confirmation) return
  busy.value = true
  try {
    const result = await invoke<{ send: NonNullable<typeof compose.sendResult> }>('confirm_send', {
      confirmationId: compose.confirmation.confirmationId,
    })
    compose.sendResult = result.send; dialog.value = false
  } catch (value) { error.value = actionable(value, t('compose.sendAction')) }
  finally { busy.value = false }
}
async function reject(): Promise<void> {
  if (!compose.confirmation) return
  try {
    await invoke('reject_send', { confirmationId: compose.confirmation.confirmationId })
    dialog.value = false
  } catch (value) { error.value = actionable(value, t('compose.cancelAction')) }
}
</script>

<template>
  <section class="workspace-grid">
    <v-card class="surface" variant="flat">
      <v-card-title>{{ t('compose.title') }}</v-card-title>
      <v-card-text>
        <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
        <v-select v-model="accounts.selectedId" :items="accounts.accounts" item-title="email" item-value="id" :label="t('compose.from')" />
        <div class="mode-switch" role="group" :aria-label="t('compose.recipientMode')">
          <v-btn data-testid="compose-mode-direct" :variant="compose.mode === 'DIRECT' ? 'tonal' : 'text'" @click="selectMode('DIRECT')">{{ t('compose.direct') }}</v-btn>
          <v-btn data-testid="compose-mode-tags" :variant="compose.mode === 'CONTACT_TAGS' ? 'tonal' : 'text'" @click="selectMode('CONTACT_TAGS')">{{ t('compose.contactTags') }}</v-btn>
        </div>
        <v-combobox v-if="compose.mode === 'DIRECT'" v-model="compose.to" chips multiple :label="t('compose.to')" />
        <v-select v-else v-model="compose.recipientTagIds" :items="contacts.tags" item-title="name" item-value="id" multiple chips :label="t('compose.contactTags')" />
        <p class="hint">{{ recipientHint }}</p>
        <v-combobox v-model="compose.cc" chips multiple :label="t('compose.cc')" />
        <v-text-field v-model="compose.subject" :label="t('compose.subject')" />
        <RichTextEditor v-model="compose.htmlText" @update:plain-text="compose.plainText = $event" />
        <div class="attachment-row">
          <v-chip v-for="item in compose.attachments" :key="item.id" closable @click:close="removeAttachment(item.id)">
            <template #prepend><FyIcon :path="mdiPaperclip" :size="16" /></template>
            <template #close><FyIcon :path="mdiCloseCircle" :size="18" /></template>
            {{ item.name }}
          </v-chip>
          <v-btn variant="tonal" @click="addAttachment">{{ t('compose.attach') }}</v-btn>
        </div>
        <p class="hint">{{ compose.draftSavedAt ? t('compose.draftSaved') : t('compose.draftPending') }}</p>
      </v-card-text>
      <v-card-actions>
        <span class="hint">{{ canReview ? t('compose.ready') : t('compose.validation') }}</span>
        <v-spacer />
        <v-btn data-testid="compose-review" color="primary" :loading="busy" :disabled="!canReview" @click="prepare">{{ t('compose.review') }}</v-btn>
      </v-card-actions>
    </v-card>
    <v-card class="surface workspace-summary" variant="flat">
      <v-card-title>{{ t('compose.previewTitle') }}</v-card-title>
      <v-card-text>
        <v-alert v-if="compose.sendResult" :type="compose.sendResult.failed ? 'warning' : 'success'" class="mb-4">
          {{ t('compose.sendResult', { sent: compose.sendResult.succeeded, failed: compose.sendResult.failed }) }}
        </v-alert>
        <h3>{{ compose.subject || t('compose.noSubject') }}</h3>
        <div class="email-preview" v-html="compose.htmlText || `<p>${t('compose.previewEmpty')}</p>`" />
      </v-card-text>
    </v-card>
  </section>
  <ConfirmationDialog v-model="dialog" :confirmation="compose.confirmation" :busy="busy" @approve="confirm" @reject="reject" />
</template>
