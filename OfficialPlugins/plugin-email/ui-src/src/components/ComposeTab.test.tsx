import { beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, waitFor } from '@testing-library/react'
import { createFengYuI18n, FengYuI18nProvider } from '@infinia/plugin-ui'
import ComposeTab from './ComposeTab'
import { messages } from '../i18n'
import { useAccountsStore, initialAccountDraft } from '../stores/accounts'
import { useComposeStore, initialComposeState, DRAFT_KEY } from '../stores/compose'
import { useContactsStore } from '../stores/contacts'

vi.mock('./RichTextEditor', () => ({ default: () => <div data-testid="rich-editor" /> }))

const bridge = vi.hoisted(() => ({
  invoke: vi.fn(),
  files: { open: vi.fn(), inputDirectory: vi.fn(), outputDirectory: vi.fn() },
}))
vi.mock('../sdk', () => ({
  ...bridge,
  actionable: (_error: unknown, action: string) => action,
  // Generated client routes every typed method to client.invoke(method, input, options); mirror that
  // here so bridge.invoke captures the exact 3-arg signature the worker bridge would receive.
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
  bridge.files.open.mockReset()
  useAccountsStore.setState({ accounts: [], selectedId: undefined, draft: initialAccountDraft })
  useContactsStore.setState({ contacts: [], tags: [], selectedTagIds: [], query: '' })
  useComposeStore.setState({ ...initialComposeState })
  window.localStorage?.removeItem(DRAFT_KEY)
})

it('prepares tag Compose and dispatches only after confirmation', async () => {
  bridge.invoke
    .mockResolvedValueOnce({ success: true, confirmation_required: true,
      confirmation: { confirmationId: 'c1', expiresAt: '2026-07-14T12:00:00Z', summary: [] } })
    .mockResolvedValueOnce({ success: true, send: { status: 'COMPLETED', succeeded: 2, failed: 0 } })
  // App populates the account list on mount; simulate that so the tab does not re-fetch.
  useAccountsStore.setState({
    accounts: [{ id: 7, displayName: 'Sender', email: 'sender@example.com' }],
    selectedId: 7,
  })
  useContactsStore.setState({ tags: [{ id: 4, name: 'Customers' }] })

  const { container } = renderWithI18n(<ComposeTab />)

  await waitFor(() => expect(bridge.invoke).not.toHaveBeenCalled())
  fireEvent.click(container.querySelector('[data-testid="compose-mode-tags"]')!)
  useComposeStore.setState({ recipientTagIds: [4], cc: ['manager@example.com'], plainText: 'Hello' })
  await waitFor(() => expect((container.querySelector('[data-testid="compose-review"]') as HTMLButtonElement).disabled).toBe(false))
  fireEvent.click(container.querySelector('[data-testid="compose-review"]')!)

  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  expect(bridge.invoke).toHaveBeenCalledWith('email_send_single',
    expect.objectContaining({ recipientTagIds: [4], cc: ['manager@example.com'] }), undefined)

  // The confirmation-first dialog is open; sending happens only through its confirm verb.
  await waitFor(() => expect(container.querySelector('[data-action="confirm"]')).toBeTruthy())
  fireEvent.click(container.querySelector('[data-action="confirm"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(2))
  expect(bridge.invoke.mock.calls.map(call => call[0])).toEqual(['email_send_single', 'confirm_send'])
})
