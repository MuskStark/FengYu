import { beforeEach, describe, expect, it } from 'vitest'
import { useAccountsStore, initialAccountDraft, selectPublicDraft } from './accounts'
import { useArchiveStore } from './archive'
import { useComposeStore, initialComposeState, selectConfirmationSummary, selectNormalizedCc, selectNormalizedTo, DRAFT_KEY } from './compose'
import { useContactsStore, selectRecipientPreview } from './contacts'
import { applyEnvironment, readEnvironment } from '../sdk'

function resetStores() {
  useAccountsStore.setState({ accounts: [], selectedId: undefined, draft: initialAccountDraft })
  useContactsStore.setState({ contacts: [], tags: [], selectedTagIds: [], query: '' })
  useComposeStore.setState({ ...initialComposeState })
  useArchiveStore.setState({
    offset: 0, limit: 25, messages: [],
    progress: { processed: 0, successful: 0, failed: 0, newArchived: 0, duplicates: 0 },
  })
}

beforeEach(() => {
  resetStores()
  window.localStorage?.removeItem(DRAFT_KEY)
})

describe('Email Center state', () => {
  it('switches accounts while passwords remain write-only', () => {
    useAccountsStore.setState({
      accounts: [{ id: 1, displayName: 'A', email: 'a@example.com' }, { id: 2, displayName: 'B', email: 'b@example.com' }],
    })
    useAccountsStore.getState().select(2)
    useAccountsStore.getState().setDraft({ id: 2, displayName: 'B', email: 'b@example.com', password: 'secret' })
    expect(useAccountsStore.getState().selectedId).toBe(2)
    expect(selectPublicDraft(useAccountsStore.getState())).not.toHaveProperty('password')
  })

  it('normalizes direct and tag recipients and confirmation summaries', () => {
    useContactsStore.setState({
      contacts: [{ id: 1, email: 'one@example.com', tagIds: [4] }, { id: 2, email: 'two@example.com', tagIds: [4, 5] }],
      selectedTagIds: [4],
    })
    expect(selectRecipientPreview(useContactsStore.getState())).toEqual(['one@example.com', 'two@example.com'])
    useComposeStore.getState().update({
      mode: 'CONTACT_TAGS',
      recipientTagIds: [4, 5],
      to: [' direct@example.com ', 'DIRECT@example.com'],
      cc: ['manager@example.com', 'direct@example.com'],
    })
    const state = useComposeStore.getState()
    expect(selectNormalizedTo(state)).toEqual(['direct@example.com'])
    expect(selectNormalizedCc(state)).toEqual(['manager@example.com'])
    useComposeStore.getState().setConfirmation({ confirmationId: 'c1', summary: [{ label: 'Recipients', value: '2' }], expiresAt: 'tomorrow' })
    expect(selectConfirmationSummary(useComposeStore.getState())).toContain('Recipients: 2')
  })

  it('persists reusable draft fields without attachments or transient protocol state', () => {
    useComposeStore.getState().update({
      mode: 'CONTACT_TAGS',
      recipientTagIds: [4],
      subject: 'Quarterly update',
      htmlText: '<p>Draft</p>',
      plainText: 'Draft',
      attachments: [{ id: 'f1', name: 'secret.pdf', kind: 'file', access: 'read', size: 1 }],
    })
    useComposeStore.getState().setConfirmation({ confirmationId: 'c1', summary: [], expiresAt: 'tomorrow' })
    useComposeStore.getState().persistDraft()

    resetStores()
    useComposeStore.getState().restoreDraft()

    const restored = useComposeStore.getState()
    expect(restored.subject).toBe('Quarterly update')
    expect(restored.htmlText).toBe('<p>Draft</p>')
    expect(restored.attachments).toEqual([])
    expect(restored.confirmation).toBeUndefined()
  })

  it('paginates archive results and tracks progress counters', () => {
    useArchiveStore.setState({ offset: 50, limit: 25 })
    useArchiveStore.getState().nextPage()
    expect(useArchiveStore.getState().offset).toBe(75)
    useArchiveStore.getState().updateProgress({ processed: 3, successful: 2, failed: 1, newArchived: 2, duplicates: 1 })
    expect(useArchiveStore.getState().progress).toMatchObject({ processed: 3, failed: 1, duplicates: 1 })
  })

  it('applies live theme and locale environment updates', () => {
    applyEnvironment({ theme: 'dark', locale: 'zh-CN' })
    expect(readEnvironment()).toEqual({ theme: 'dark', locale: 'zh-CN' })
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.lang).toBe('zh-CN')
  })
})
