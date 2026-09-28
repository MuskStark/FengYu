import { create } from 'zustand'
import type { FileRef } from '@infinia/plugin-sdk'
import type { Confirmation, SendResult } from './compose'

export interface BatchPreviewMessage {
  attachmentTag?: string
  to: string[]
  cc: string[]
  tagAttachments: string[]
  commonAttachments: string[]
}
export interface BatchSkippedTag { attachmentTag?: string; reason: string; attachments: string[] }
export interface BatchPreview {
  messages: BatchPreviewMessage[]
  ignoredFiles: string[]
  skippedTags: BatchSkippedTag[]
  messageCount?: number
}

const emptyPreview = (): BatchPreview => ({ messages: [], ignoredFiles: [], skippedTags: [], messageCount: 0 })

export interface BatchState {
  inputDirectory: FileRef | null
  recipientGroupTagIds: number[]
  ccGroupTagIds: number[]
  commonAttachments: FileRef[]
  subject: string
  htmlText: string
  plainText: string
  preview: BatchPreview
  confirmation?: Confirmation
  sendResult?: SendResult
  update: (partial: Partial<BatchState>) => void
  applyPreview: (value?: BatchPreview) => void
  clearPreview: () => void
  messageCount: () => number
}

export const useBatchStore = create<BatchState>((set, get) => ({
  inputDirectory: null,
  recipientGroupTagIds: [],
  ccGroupTagIds: [],
  commonAttachments: [],
  subject: '',
  htmlText: '',
  plainText: '',
  preview: emptyPreview(),
  confirmation: undefined,
  sendResult: undefined,
  update: partial => set(partial),
  applyPreview: value => set({ preview: value ?? emptyPreview() }),
  clearPreview: () => set({ preview: emptyPreview() }),
  messageCount: () => get().preview.messages.length,
}))
