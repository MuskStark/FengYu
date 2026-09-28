import { beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, waitFor } from '@testing-library/react'
import { createFengYuI18n, FengYuI18nProvider } from '@infinia/plugin-ui'
import ImportContactsDialog from './ImportContactsDialog'
import { messages } from '../i18n'

// Bridge that captures invoke calls and simulates file picking, mirroring the ComposeTab test pattern.
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

function renderDialog(overrides: { onImported?: () => void; onClose?: () => void } = {}) {
  const onImported = overrides.onImported ?? vi.fn()
  const onClose = overrides.onClose ?? vi.fn()
  return {
    ...render(
      <FengYuI18nProvider i18n={createFengYuI18n(messages)}>
        <ImportContactsDialog open onClose={onClose} onImported={onImported} />
      </FengYuI18nProvider>,
    ),
    onImported,
    onClose,
  }
}

beforeEach(() => {
  bridge.invoke.mockReset()
  bridge.files.open.mockReset()
})

// jsdom lacks a real click() that downloads — stub the anchor + URL.createObjectURL so the
// template-download path can be asserted without a browser.
const download = vi.hoisted(() => ({ clicked: false, filename: '', url: '' }))
beforeEach(() => {
  download.clicked = false; download.filename = ''; download.url = ''
  vi.stubGlobal('URL', Object.assign(vi.fn(() => 'blob:template'), URL, {
    createObjectURL: vi.fn(() => 'blob:template'),
    revokeObjectURL: vi.fn(),
  }))
  const realCreateElement = document.createElement.bind(document)
  vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
    const el = realCreateElement(tag)
    if (tag === 'a') {
      el.click = () => { download.clicked = true; download.filename = (el as HTMLAnchorElement).download; download.url = (el as HTMLAnchorElement).href }
    }
    return el
  })
})

it('previews the import then commits with the selected duplicate mode', async () => {
  bridge.files.open.mockResolvedValueOnce({ id: 'ref_1', name: 'contacts.csv', kind: 'file', access: 'read', size: 100 })
  bridge.invoke
    .mockResolvedValueOnce({ success: true, preview: { rowsTotal: 5, rowsValid: 4, createdContacts: 3, mergedContacts: 1, skippedContacts: 0, createdTags: ['VIP'], errors: [{ row: 8, message: 'bad email' }] } })
    .mockResolvedValueOnce({ success: true, result: { created: 3, merged: 1, skipped: 0, tagsCreated: 1, tagsAssigned: 4, errors: [{ row: 8, message: 'bad email' }] } })

  const { container, onImported, onClose } = renderDialog()

  // Step 1 — pick a file and preview.
  fireEvent.click(container.querySelector('[data-testid="import-choose-file"]')!)
  await waitFor(() => expect(bridge.files.open).toHaveBeenCalledTimes(1))
  fireEvent.click(container.querySelector('[data-testid="import-preview-btn"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  expect(bridge.invoke.mock.calls[0][0]).toBe('email_contacts_import_preview')
  expect(bridge.invoke.mock.calls[0][1]).toMatchObject({ duplicateMode: 'merge' })

  // Step 2 — preview rendered, then confirm.
  expect(container.querySelector('[data-testid="import-preview-step"]')).toBeTruthy()
  fireEvent.click(container.querySelector('[data-testid="import-confirm-btn"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(2))
  expect(bridge.invoke.mock.calls[1][0]).toBe('email_contacts_import_commit')
  // The selected duplicate mode is forwarded to commit, keeping the two calls symmetric.
  expect(bridge.invoke.mock.calls[1][1]).toMatchObject({ duplicateMode: 'merge' })

  // On success the dialog closes and reports 'imported' so the parent can reload.
  await waitFor(() => {
    expect(onImported).toHaveBeenCalledTimes(1)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

it('prevents preview when no file is picked', () => {
  const { container } = renderDialog()
  // No file picked → the preview button is disabled; no RPC is dispatched.
  expect((container.querySelector('[data-testid="import-preview-btn"]') as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(container.querySelector('[data-testid="import-preview-btn"]')!)
  expect(bridge.invoke).not.toHaveBeenCalled()
})

it('passes skip mode through when the radio changes', async () => {
  bridge.files.open.mockResolvedValueOnce({ id: 'ref_2', name: 'c.xlsx', kind: 'file', access: 'read', size: 200 })
  bridge.invoke.mockResolvedValueOnce({ success: true, preview: { rowsTotal: 1, rowsValid: 1, createdContacts: 1, mergedContacts: 0, skippedContacts: 0, createdTags: [], errors: [] } })

  const { container } = renderDialog()
  fireEvent.click(container.querySelector('[data-testid="import-choose-file"]')!)
  await waitFor(() => expect(bridge.files.open).toHaveBeenCalledTimes(1))
  // Switch duplicate mode through the native radio group.
  fireEvent.click(container.querySelector('[data-testid="import-mode-skip"]')!)
  fireEvent.click(container.querySelector('[data-testid="import-preview-btn"]')!)
  await waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  expect(bridge.invoke.mock.calls[0][1]).toMatchObject({ duplicateMode: 'skip' })
})

it('downloads a CSV template named contacts-template.csv on click', () => {
  const { container } = renderDialog()
  fireEvent.click(container.querySelector('[data-testid="import-download-template"]')!)
  expect(download.clicked).toBe(true)
  expect(download.filename).toBe('contacts-template.csv')
})
