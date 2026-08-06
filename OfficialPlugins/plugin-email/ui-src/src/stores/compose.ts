import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { FileRef } from '@infinia/plugin-sdk'
import { sanitizeEmailHtml } from '../richText'

export type ComposeMode = 'DIRECT' | 'CONTACT_TAGS'
export interface SummaryRow { label: string; value: string; group?: string }
export interface Confirmation { confirmationId: string; summary: SummaryRow[]; expiresAt: string; approveMethod?: string; rejectMethod?: string }
export interface SendResult { status: string; succeeded: number; failed: number; failedRecipients?: string[] }

const DRAFT_KEY = 'fengyu.email.compose.v1'
const memoryDraft = new Map<string, string>()

function draftStorage(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> {
  try {
    if (window.localStorage) return window.localStorage
  } catch { /* non-persistent test/privacy context */ }
  return {
    getItem: key => memoryDraft.get(key) ?? null,
    setItem: (key, value) => { memoryDraft.set(key, value) },
    removeItem: key => { memoryDraft.delete(key) },
  }
}

function normalizeAddresses(values: string[]): string[] {
  const normalized = new Map<string, string>()
  for (const value of values ?? []) {
    const trimmed = value?.trim()
    if (trimmed) normalized.set(trimmed.toLowerCase(), trimmed.toLowerCase())
  }
  return [...normalized.values()].sort()
}

export const useComposeStore = defineStore('email-compose', () => {
  const mode = ref<ComposeMode>('DIRECT')
  const recipientTagIds = ref<number[]>([])
  const to = ref<string[]>([]), cc = ref<string[]>([])
  const subject = ref(''), plainText = ref(''), htmlText = ref('')
  const attachments = ref<FileRef[]>([])
  const confirmation = ref<Confirmation>()
  const sendResult = ref<SendResult>()
  const draftSavedAt = ref<string>()
  const normalizedTo = computed(() => normalizeAddresses(to.value))
  const normalizedCc = computed(() => {
    const primary = new Set(normalizedTo.value)
    return normalizeAddresses(cc.value).filter(address => !primary.has(address))
  })
  const confirmationSummary = computed(() => confirmation.value
    ? `${confirmation.value.summary.map(row => `${row.label}: ${row.value}`).join(' · ')} · expires ${confirmation.value.expiresAt}`
    : '')

  function setConfirmation(value: Confirmation) { confirmation.value = value }
  function clearTransient() { confirmation.value = undefined; sendResult.value = undefined }
  function persistDraft() {
    const draft = {
      mode: mode.value,
      recipientTagIds: recipientTagIds.value,
      to: normalizedTo.value,
      cc: normalizedCc.value,
      subject: subject.value,
      htmlText: sanitizeEmailHtml(htmlText.value),
      plainText: plainText.value,
    }
    draftStorage().setItem(DRAFT_KEY, JSON.stringify(draft))
    draftSavedAt.value = new Date().toISOString()
  }
  function restoreDraft() {
    const serialized = draftStorage().getItem(DRAFT_KEY)
    if (!serialized) return
    try {
      const draft = JSON.parse(serialized) as Partial<{
        mode: ComposeMode; recipientTagIds: number[]; to: string[]; cc: string[];
        subject: string; htmlText: string; plainText: string
      }>
      mode.value = draft.mode === 'CONTACT_TAGS' ? 'CONTACT_TAGS' : 'DIRECT'
      recipientTagIds.value = Array.isArray(draft.recipientTagIds) ? draft.recipientTagIds : []
      to.value = Array.isArray(draft.to) ? draft.to : []
      cc.value = Array.isArray(draft.cc) ? draft.cc : []
      subject.value = draft.subject ?? ''
      htmlText.value = sanitizeEmailHtml(draft.htmlText ?? '')
      plainText.value = draft.plainText ?? ''
    } catch { draftStorage().removeItem(DRAFT_KEY) }
  }

  return { mode, recipientTagIds, to, cc, subject, plainText, htmlText, attachments,
    confirmation, sendResult, draftSavedAt, normalizedTo, normalizedCc, confirmationSummary,
    setConfirmation, clearTransient, persistDraft, restoreDraft }
})
