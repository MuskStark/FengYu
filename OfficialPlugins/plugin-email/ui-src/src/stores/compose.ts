import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { FileRef } from '@fengyu/plugin-sdk'

export interface Confirmation { confirmationId: string; summary: string; expiresAt: string }

export const useComposeStore = defineStore('email-compose', () => {
  const to = ref<string[]>([]), cc = ref<string[]>([]), bcc = ref<string[]>([])
  const subject = ref(''), plainText = ref(''), htmlText = ref('')
  const attachments = ref<FileRef[]>([])
  const filenameRecipients = ref<string[]>([])
  const confirmation = ref<Confirmation>()
  const confirmationSummary = computed(() => confirmation.value ? `${confirmation.value.summary} · expires ${confirmation.value.expiresAt}` : '')
  function setFilenamePreview(names: string[]) { filenameRecipients.value = names.filter(name => name.includes('.')).map(name => name.replace(/\.[^.]+$/, '')) }
  function setConfirmation(value: Confirmation) { confirmation.value = value }
  return { to, cc, bcc, subject, plainText, htmlText, attachments, filenameRecipients, confirmation, confirmationSummary, setFilenamePreview, setConfirmation }
})
