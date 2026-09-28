import { beforeEach, describe, expect, it } from 'vitest'
import { useBatchStore } from './batch'

describe('batch send state', () => {
  beforeEach(() => {
    useBatchStore.setState({
      inputDirectory: null,
      recipientGroupTagIds: [],
      ccGroupTagIds: [],
      commonAttachments: [],
      subject: '',
      htmlText: '',
      plainText: '',
      preview: { messages: [], ignoredFiles: [], skippedTags: [], messageCount: 0 },
      confirmation: undefined,
      sendResult: undefined,
    })
  })

  it('keeps group intersections, common attachments, and per-tag preview metadata', () => {
    const store = useBatchStore.getState()
    store.update({
      recipientGroupTagIds: [10],
      ccGroupTagIds: [11],
      commonAttachments: [{ id: 'f1', name: 'terms.pdf', kind: 'file', access: 'read', size: 128 }],
    })
    useBatchStore.getState().applyPreview({
      messages: [{ attachmentTag: 'East', to: ['a@example.com', 'b@example.com'],
        cc: ['manager@example.com'], tagAttachments: ['report_East.pdf'], commonAttachments: ['terms.pdf'] }],
      ignoredFiles: ['README'], skippedTags: [], messageCount: 1,
    })
    expect(useBatchStore.getState().messageCount()).toBe(1)
    expect(useBatchStore.getState().preview.messages[0].commonAttachments).toEqual(['terms.pdf'])
  })
})
