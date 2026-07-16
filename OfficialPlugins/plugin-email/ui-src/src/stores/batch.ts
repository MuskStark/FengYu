import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { FileRef } from '@infinia/plugin-sdk'
import type { Confirmation, SendResult } from './compose'

export interface BatchPreviewMessage {
  attachmentTag: string
  to: string[]
  cc: string[]
  tagAttachments: string[]
  commonAttachments: string[]
}
export interface BatchSkippedTag { attachmentTag: string; reason: string; attachments: string[] }
export interface BatchPreview {
  messages: BatchPreviewMessage[]
  ignoredFiles: string[]
  skippedTags: BatchSkippedTag[]
  messageCount?: number
}

const emptyPreview = (): BatchPreview => ({ messages: [], ignoredFiles: [], skippedTags: [], messageCount: 0 })

export const useBatchStore = defineStore('email-batch', () => {
  const inputDirectory = ref<FileRef | null>(null)
  const recipientGroupTagIds = ref<number[]>([])
  const ccGroupTagIds = ref<number[]>([])
  const commonAttachments = ref<FileRef[]>([])
  const subject = ref('')
  const htmlText = ref('')
  const plainText = ref('')
  const preview = ref<BatchPreview>(emptyPreview())
  const confirmation = ref<Confirmation>()
  const sendResult = ref<SendResult>()
  const messageCount = computed(() => preview.value.messages.length)
  function applyPreview(value?: BatchPreview) { preview.value = value ?? emptyPreview() }
  function clearPreview() { preview.value = emptyPreview() }
  return { inputDirectory, recipientGroupTagIds, ccGroupTagIds, commonAttachments,
    subject, htmlText, plainText, preview, confirmation, sendResult, messageCount, applyPreview, clearPreview }
})
