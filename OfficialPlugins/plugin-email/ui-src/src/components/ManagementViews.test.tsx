import { beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, waitFor } from '@testing-library/react'
import { createFengYuI18n, FengYuI18nProvider } from '@infinia/plugin-ui'
import SendRecordsView from './SendRecordsView'
import AccountSettingsView from './AccountSettingsView'
import AddressBookTab from './AddressBookTab'
import CollectTab from './CollectTab'
import { messages } from '../i18n'
import { useAccountsStore, initialAccountDraft } from '../stores/accounts'
import { useArchiveStore } from '../stores/archive'
import { useContactsStore } from '../stores/contacts'

const bridge = vi.hoisted(() => ({ invoke: vi.fn(), files: { open: vi.fn(), inputDirectory: vi.fn(), outputDirectory: vi.fn() } }))
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
  useArchiveStore.setState({
    offset: 0, limit: 25, messages: [],
    progress: { processed: 0, successful: 0, failed: 0, newArchived: 0, duplicates: 0 },
  })
})

it('renders structured send records without retry controls or raw JSON', async () => {
  bridge.invoke.mockResolvedValue({ success: true, tasks: [{ confirmationId: 'c1', status: 'PARTIAL_FAILED', mode: 'ATTACHMENT_TAGS', updatedAt: '2026-07-14T10:00:00Z' }], messages: [{ id: 1, confirmationId: 'c1', subject: 'Quarterly', status: 'FAILED', accountEmail: 'mail@example.com', errorMessage: 'Mailbox rejected' }] })
  const { container } = renderWithI18n(<SendRecordsView />)
  await waitFor(() => expect(container.querySelectorAll('tbody tr').length).toBeGreaterThan(0))
  fireEvent.change(container.querySelector('[data-testid="record-search"]')!, { target: { value: 'c1' } })
  fireEvent.click(container.querySelector('[data-testid="record-search-submit"]')!)
  await waitFor(() => expect(container.textContent).toContain('Partially failed'))
  expect(bridge.invoke).toHaveBeenCalledWith('email_send_records_query', expect.objectContaining({ query: 'c1', offset: 0 }), undefined)
  expect(container.querySelector('pre')).toBeNull()
  expect((container.textContent ?? '').toLowerCase()).not.toContain('retry')
})

it('expands a task row and folds its recipients beyond the first three', async () => {
  bridge.invoke.mockImplementation((_method: string, input?: { confirmationId?: string }) =>
    input?.confirmationId
      ? Promise.resolve({ success: true, tasks: [], messages: [{ id: 9, confirmationId: input.confirmationId, subject: 'Quarterly', status: 'SUCCESS', accountEmail: 'mail@example.com', sentAt: '2026-07-14T11:00:00Z', recipientsJson: '{"to":["a@example.com","b@example.com","c@example.com","d@example.com"],"cc":[],"bcc":[]}' }] })
      : Promise.resolve({ success: true, tasks: [{ confirmationId: 'c1', status: 'SENDING', mode: 'SINGLE', updatedAt: '2026-07-14T10:00:00Z' }], messages: [] }))
  const { container } = renderWithI18n(<SendRecordsView />)
  await waitFor(() => expect(container.querySelectorAll('[data-testid="task-toggle"]')).toHaveLength(1))
  fireEvent.click(container.querySelector('[data-testid="task-toggle"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledWith('email_send_records_query',
    expect.objectContaining({ confirmationId: 'c1' }), undefined))
  await waitFor(() => expect(container.textContent).toContain('a@example.com'))
  expect(container.textContent).toContain('+1')
  expect(container.textContent).not.toContain('d@example.com')
  expect(container.textContent).toContain('Sent')
})

it('keeps account passwords write-only and separates test from save', async () => {
  bridge.invoke.mockResolvedValue({ success: true, accounts: [] })
  const { container } = renderWithI18n(<AccountSettingsView />)
  expect(container.querySelector('input[type="password"]')!.getAttribute('autocomplete')).toBe('new-password')
  fireEvent.click(container.querySelector('[data-testid="smtp-test"]')!)
  // The test action is async (guarded); wait for its RPC to land before the next click.
  await waitFor(() => expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test', expect.any(Object), undefined))
  // SMTP and IMAP each have their own test button dispatching distinct methods.
  expect(container.querySelector('[data-testid="imap-test"]')).toBeTruthy()
  fireEvent.click(container.querySelector('[data-testid="imap-test"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test_imap', expect.any(Object), undefined))
  fireEvent.click(container.querySelector('[data-testid="account-save"]')!)
  // save dispatches email_account_save, then refreshes the list via email_accounts_list. The generated
  // client always passes three positional args (input {} + undefined options), so assert the full
  // signature for the accounts-list refresh call.
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledWith('email_account_save', expect.any(Object), undefined))
  await waitFor(() => expect(bridge.invoke).toHaveBeenLastCalledWith('email_accounts_list', {}, undefined))
})

it('keeps bulk contact actions separate from tag management', async () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [] })
  const { container } = renderWithI18n(<AddressBookTab />)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalled())
  expect(container.querySelector('[data-testid="contact-list-scroll"]')).toBeTruthy()
  expect(container.querySelector('[data-testid="contact-bulk-tags"]')).toBeTruthy()
  // tag manager is an always-visible card, not a dialog opened by a button
  expect(container.querySelector('[data-testid="tag-manager-card"]')).toBeTruthy()
  expect(container.querySelector('[data-testid="tag-manager-dialog"]')).toBeNull()
  expect(container.querySelector('[data-testid="tag-manager-open"]')).toBeNull()
})

it('folds contact tag pills beyond the first two', async () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [{ id: 1, email: 'a@example.com', tagIds: [10, 20, 30] }], tags: [{ id: 10, name: '客户' }, { id: 20, name: 'VIP' }, { id: 30, name: '内部' }] })
  const { container } = renderWithI18n(<AddressBookTab />)
  // The mount effect triggers an async store.load(); wait for the row to render before asserting the fold.
  await waitFor(() => expect(container.querySelectorAll('[data-testid="contact-row"]')).toHaveLength(1))
  expect(container.querySelector('[data-testid="contact-row"]')!.textContent).toContain('+1')
})

it('filters the tag manager list by the search query', async () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [{ id: 1, name: '客户' }, { id: 2, name: 'VIP' }, { id: 3, name: '供应商' }] })
  const { container } = renderWithI18n(<AddressBookTab />)
  // The mount effect triggers an async store.load(); wait for the tag rows to render before searching.
  await waitFor(() => expect(container.querySelectorAll('[data-testid="tag-manager-row"]')).toHaveLength(3))
  fireEvent.change(container.querySelector('input[data-testid="tag-search"]')!, { target: { value: 'vi' } })
  const rows = container.querySelectorAll('[data-testid="tag-manager-row"]')
  expect(rows).toHaveLength(1)
  expect(rows[0].textContent).toContain('VIP')
})

it('saves the contact with tags selected in the contact form', async () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [{ id: 7, name: 'Clients' }] })
  const { container } = renderWithI18n(<AddressBookTab />)
  await waitFor(() => expect(container.querySelectorAll('[data-testid="tag-manager-row"]')).toHaveLength(1))
  // Select a tag in the contact form, type the email, then save.
  fireEvent.click(container.querySelector('[data-testid="contact-tag-7"]')!)
  fireEvent.change(container.querySelector('[data-testid="contact-email"]')!, { target: { value: 'tagged@example.com' } })
  fireEvent.click(container.querySelector('[data-testid="contact-save"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledWith('email_contact_save',
    expect.objectContaining({ email: 'tagged@example.com', tagIds: [7] }), undefined))
})

it('shows collection counters and archive pagination together', async () => {
  useArchiveStore.getState().updateProgress({ processed: 8, successful: 5, failed: 1, newArchived: 5, duplicates: 2 })
  bridge.invoke.mockResolvedValue({ success: true })
  const { container } = renderWithI18n(<CollectTab />)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalled())
  expect(container.querySelector('[data-testid="archive-progress"]')!.textContent).toContain('8')
  expect(container.querySelector('[data-testid="archive-results"]')).toBeTruthy()
  expect(container.querySelector('[data-testid="archive-next-page"]')).toBeTruthy()
})
