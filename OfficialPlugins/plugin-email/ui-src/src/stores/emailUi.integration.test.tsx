import { beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, waitFor } from '@testing-library/react'
import { createFengYuI18n, FengYuI18nProvider } from '@infinia/plugin-ui'
import BatchTab from '../components/BatchTab'
import { messages } from '../i18n'
import { useAccountsStore, initialAccountDraft } from './accounts'
import { useBatchStore } from './batch'
import { useContactsStore } from './contacts'

vi.mock('../components/RichTextEditor', () => ({ default: () => <div data-testid="rich-editor" /> }))

const bridge = vi.hoisted(() => ({
  invoke: vi.fn(),
  files: { open: vi.fn(), inputDirectory: vi.fn(), outputDirectory: vi.fn() },
}))
vi.mock('../sdk', () => ({
  ...bridge,
  actionable: (_error: unknown, action: string) => action,
  rpc: new Proxy({}, {
    get: (_t, prop) => typeof prop === 'string' && prop !== 'then'
      ? (input?: unknown, options?: unknown) => bridge.invoke(prop, input, options)
      : undefined,
  }),
  checked: async (p: Promise<{ success: boolean; summary: string }>) => {
    const r = await p
    if (!r.success) throw new Error(r.summary || 'Email operation failed')
    return r
  },
}))

function renderWithI18n(ui: React.ReactElement) {
  return render(<FengYuI18nProvider i18n={createFengYuI18n(messages)}>{ui}</FengYuI18nProvider>)
}

beforeEach(() => {
  bridge.invoke.mockReset()
  useAccountsStore.setState({ accounts: [], selectedId: undefined, draft: initialAccountDraft })
  useContactsStore.setState({ contacts: [], tags: [], selectedTagIds: [], query: '' })
  useBatchStore.setState({
    inputDirectory: null, recipientGroupTagIds: [], ccGroupTagIds: [], commonAttachments: [],
    subject: '', htmlText: '', plainText: '',
    preview: { messages: [], ignoredFiles: [], skippedTags: [], messageCount: 0 },
    confirmation: undefined, sendResult: undefined,
  })
})

it('previews the exact batch parameters before preparing and only sends after confirmation', async () => {
  bridge.invoke
    .mockResolvedValueOnce({ success: true, preview: { messages: [{ attachmentTag: 'East', to: ['a@example.com'], cc: [], tagAttachments: ['report_East.pdf'], commonAttachments: ['terms.pdf'] }], ignoredFiles: [], skippedTags: [], messageCount: 1 } })
    .mockResolvedValueOnce({ success: true, confirmation: { confirmationId: 'c1', expiresAt: '2026-07-14T12:00:00Z', summary: [] } })
    .mockResolvedValueOnce({ success: true, send: { status: 'COMPLETED', succeeded: 1, failed: 0 } })
  useAccountsStore.setState({ selectedId: 7 })
  useContactsStore.setState({ tags: [{ id: 10, name: 'Customers' }, { id: 11, name: 'Managers' }] })
  useBatchStore.setState({
    inputDirectory: { id: 'd1', name: 'reports', kind: 'directory', access: 'read', size: 0 },
    recipientGroupTagIds: [10],
    ccGroupTagIds: [11],
    commonAttachments: [{ id: 'f1', name: 'terms.pdf', kind: 'file', access: 'read', size: 10 }],
    plainText: 'Please review',
  })

  const { container } = renderWithI18n(<BatchTab />)

  await waitFor(() => expect(bridge.invoke).not.toHaveBeenCalled())
  fireEvent.click(container.querySelector('[data-testid="batch-refresh"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  const previewParams = bridge.invoke.mock.calls[0][1]
  expect(bridge.invoke.mock.calls[0][0]).toBe('email_batch_preview')
  expect(previewParams).toMatchObject({ recipientGroupTagIds: [10], ccGroupTagIds: [11], inputDirectory: useBatchStore.getState().inputDirectory, commonAttachments: useBatchStore.getState().commonAttachments })

  fireEvent.click(container.querySelector('[data-testid="batch-review"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(2))
  expect(bridge.invoke.mock.calls[1]).toEqual(['email_send_batch', previewParams, undefined])
  expect(bridge.invoke).toHaveBeenCalledTimes(2)

  await waitFor(() => expect(container.querySelector('[data-action="confirm"]')).toBeTruthy())
  fireEvent.click(container.querySelector('[data-action="confirm"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(3))
  expect(bridge.invoke.mock.calls.map(call => call[0])).toEqual(['email_batch_preview', 'email_send_batch', 'confirm_send'])
})
