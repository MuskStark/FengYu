import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useBatchStore } from './batch'

beforeEach(() => setActivePinia(createPinia()))

describe('batch send state', () => {
  it('keeps group intersections, common attachments, and per-tag preview metadata', () => {
    const store = useBatchStore()
    store.recipientGroupTagIds = [10]
    store.ccGroupTagIds = [11]
    store.commonAttachments = [{ id: 'f1', name: 'terms.pdf', kind: 'file', access: 'read', size: 128 }]
    store.applyPreview({
      messages: [{ attachmentTag: 'East', to: ['a@example.com', 'b@example.com'],
        cc: ['manager@example.com'], tagAttachments: ['report_East.pdf'], commonAttachments: ['terms.pdf'] }],
      ignoredFiles: ['README'], skippedTags: [], messageCount: 1,
    })
    expect(store.messageCount).toBe(1)
    expect(store.preview.messages[0].commonAttachments).toEqual(['terms.pdf'])
  })
})
