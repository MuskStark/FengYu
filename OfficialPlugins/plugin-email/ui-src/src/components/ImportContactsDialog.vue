<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FileRef } from '@infinia/plugin-sdk'
import { actionable, checked, files, rpc } from '../sdk'

const { t } = useI18n()

const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'imported'): void }>()
defineProps<{ modelValue: boolean }>()

type ImportPreview = {
  rowsTotal: number; rowsValid: number; createdContacts: number; mergedContacts: number; skippedContacts: number
  createdTags: string[]; errors: { row: number; message: string }[]
}
type ImportResult = {
  created: number; merged: number; skipped: number; tagsCreated: number; tagsAssigned: number
  errors: { row: number; message: string }[]
}

const fileRef = ref<FileRef | null>(null)
const duplicateMode = ref<'merge' | 'skip' | 'overwrite'>('merge')
const error = ref('')
const info = ref('')
const busy = ref(false)
const preview = ref<ImportPreview | null>(null)

function reset(): void {
  fileRef.value = null
  duplicateMode.value = 'merge'
  error.value = ''
  info.value = ''
  preview.value = null
  busy.value = false
}

function close(): void {
  reset()
  emit('update:modelValue', false)
}

/**
 * Generates and downloads a CSV template in the browser. The header uses the
 * import aliases the parser always recognizes, so the exported file imports
 * back unchanged. A leading UTF-8 BOM is prepended so Excel detects the encoding
 * and renders any later-added non-ASCII text correctly.
 */
function downloadTemplate(): void {
  const rows = [
    'email,name,notes,tags',
    'alice@example.com,Alice Chen,Big client,Marketing|VIP',
    'bob@example.com,Bob Stone,,Sales;Priority',
  ]
  const csv = '\uFEFF' + rows.join('\r\n') + '\r\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'contacts-template.csv'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

async function chooseFile(): Promise<void> {
  try {
    error.value = ''
    const picked = await files.open()
    fileRef.value = picked
  } catch (value) {
    error.value = actionable(value, t('contacts.importChooseFile'))
  }
}

async function runPreview(): Promise<void> {
  if (!fileRef.value) { error.value = t('contacts.importNoFile'); return }
  busy.value = true; error.value = ''; preview.value = null
  try {
    const result = await checked(rpc.email_contacts_import_preview({
      sourceFile: fileRef.value as unknown as string,
      duplicateMode: duplicateMode.value,
    }))
    preview.value = result.preview
  } catch (value) {
    error.value = actionable(value, t('contacts.importPreviewAction'))
  } finally {
    busy.value = false
  }
}

async function runCommit(): Promise<void> {
  if (!fileRef.value) return
  busy.value = true; error.value = ''
  try {
    const result = await checked(rpc.email_contacts_import_commit({
      sourceFile: fileRef.value as unknown as string,
      duplicateMode: duplicateMode.value,
    }))
    const outcome = result.result
    info.value = t('contacts.importDone', {
      created: outcome.created, merged: outcome.merged, skipped: outcome.skipped, tags: outcome.tagsCreated,
    })
    emit('imported')
    close()
  } catch (value) {
    error.value = actionable(value, t('contacts.importAction'))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="560" @update:model-value="value => { if (!value) close() }">
    <v-card class="surface" variant="flat">
      <v-card-title>{{ t('contacts.importTitle') }}</v-card-title>
      <v-card-text>
        <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
        <v-alert v-if="info" type="success" class="mb-4">{{ info }}</v-alert>

        <!-- Step 1: pick file + duplicate mode -->
        <div v-if="!preview">
          <div class="text-subtitle-2 mb-2">{{ t('contacts.importStep1') }}</div>
          <div class="d-flex align-center ga-2 mb-2">
            <v-btn variant="outlined" data-testid="import-choose-file" @click="chooseFile">{{ t('contacts.importChooseFile') }}</v-btn>
            <span class="text-body-2">{{ fileRef?.name ?? t('contacts.importNoFile') }}</span>
          </div>
          <div class="text-caption text-medium-emphasis mb-2">{{ t('contacts.importFileHint') }}</div>
          <div class="d-flex align-center ga-2 mb-4">
            <v-btn variant="text" size="small" data-testid="import-download-template" @click="downloadTemplate">{{ t('contacts.importDownloadTemplate') }}</v-btn>
            <span class="text-caption text-medium-emphasis">{{ t('contacts.importTemplateHint') }}</span>
          </div>
          <div class="text-subtitle-2 mb-1">{{ t('contacts.importDuplicates') }}</div>
          <v-radio-group v-model="duplicateMode" inline density="compact" data-testid="import-duplicate-mode" hide-details>
            <v-radio :label="t('contacts.importMerge')" value="merge" />
            <v-radio :label="t('contacts.importSkip')" value="skip" />
            <v-radio :label="t('contacts.importOverwrite')" value="overwrite" />
          </v-radio-group>
        </div>

        <!-- Step 2: preview -->
        <div v-else data-testid="import-preview-step">
          <div class="text-subtitle-2 mb-2">{{ t('contacts.importStep2') }}</div>
          <v-alert type="info" variant="tonal" class="mb-3" data-testid="import-parsed-summary">
            {{ t('contacts.importParsed', { total: preview.rowsTotal, valid: preview.rowsValid, errors: preview.errors.length }) }}
          </v-alert>
          <ul class="import-summary mb-2">
            <li data-testid="import-create-line">{{ t('contacts.importCreate', { count: preview.createdContacts }) }}</li>
            <li data-testid="import-merge-line">{{ t('contacts.importMergeCount', { count: preview.mergedContacts }) }}</li>
            <li data-testid="import-skip-line">{{ t('contacts.importSkipCount', { count: preview.skippedContacts }) }}</li>
            <li v-if="preview.createdTags.length" data-testid="import-tags-line">
              {{ t('contacts.importTagsCreate', { count: preview.createdTags.length, names: preview.createdTags.join(', ') }) }}
            </li>
          </ul>
          <div v-if="preview.errors.length" class="mt-2">
            <div class="text-subtitle-2">{{ t('contacts.importErrors', { count: preview.errors.length }) }}</div>
            <div v-for="(item, index) in preview.errors" :key="index" class="text-caption text-medium-emphasis">
              {{ t('contacts.importErrors', { count: preview.errors.length }) }} · {{ item.row }}: {{ item.message }}
            </div>
          </div>
        </div>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" :disabled="busy" @click="close">{{ t('common.cancel') }}</v-btn>
        <template v-if="!preview">
          <v-btn data-testid="import-preview-btn" color="primary" :loading="busy" :disabled="!fileRef" @click="runPreview">{{ t('contacts.importPreview') }}</v-btn>
        </template>
        <template v-else>
          <v-btn variant="text" :disabled="busy" @click="preview = null">{{ t('contacts.importBack') }}</v-btn>
          <v-btn data-testid="import-confirm-btn" color="primary" :loading="busy" @click="runCommit">{{ t('contacts.importConfirm') }}</v-btn>
        </template>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.import-summary { list-style: none; padding-left: 0; }
.import-summary li { padding: 2px 0; }
</style>
