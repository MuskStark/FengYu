import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { FileRef } from '@fengyu/plugin-sdk'

export interface SummaryRow { label: string; value: string }
export interface Confirmation { confirmationId: string; summary: SummaryRow[]; expiresAt: string; approveMethod?: string; rejectMethod?: string }
export interface SendResult { status: string; succeeded: number; failed: number; failedRecipients: string[] }

export const useComposeStore = defineStore('email-compose', () => {
  const to = ref<string[]>([]), cc = ref<string[]>([]), bcc = ref<string[]>([])
  const subject = ref(''), plainText = ref(''), htmlText = ref('')
  const attachments = ref<FileRef[]>([])
  const filenameRecipients = ref<string[]>([])
  const confirmation = ref<Confirmation>()
  const sendResult = ref<SendResult>()
  const confirmationSummary = computed(() => confirmation.value ? `${confirmation.value.summary.map(row => `${row.label}: ${row.value}`).join(' · ')} · expires ${confirmation.value.expiresAt}` : '')
  function setFilenamePreview(names: string[]) { filenameRecipients.value = names.map(name => name.replace(/\.[^.]+$/, '')).filter(name => name.includes('_')).map(name => name.slice(name.lastIndexOf('_') + 1)) }
  function setConfirmation(value: Confirmation) { confirmation.value = value }
  return { to, cc, bcc, subject, plainText, htmlText, attachments, filenameRecipients, confirmation, sendResult, confirmationSummary, setFilenamePreview, setConfirmation }
})
