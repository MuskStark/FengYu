import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAccountsStore } from './accounts'
import { useArchiveStore } from './archive'
import { useComposeStore } from './compose'
import { useContactsStore } from './contacts'
import { applyEnvironment, readEnvironment } from '../sdk'

beforeEach(() => setActivePinia(createPinia()))

describe('Email Center state', () => {
  it('switches accounts while passwords remain write-only', () => {
    const store = useAccountsStore()
    store.accounts = [{ id: 1, displayName: 'A', email: 'a@example.com' }, { id: 2, displayName: 'B', email: 'b@example.com' }]
    store.select(2)
    store.setDraft({ id: 2, displayName: 'B', email: 'b@example.com', password: 'secret' })
    expect(store.selectedId).toBe(2)
    expect(store.publicDraft).not.toHaveProperty('password')
  })

  it('normalizes direct and tag recipients and confirmation summaries', () => {
    const contacts = useContactsStore()
    contacts.contacts = [{ id: 1, email: 'one@example.com', tagIds: [4] }, { id: 2, email: 'two@example.com', tagIds: [4, 5] }]
    contacts.selectedTagIds = [4]
    expect(contacts.recipientPreview).toEqual(['one@example.com', 'two@example.com'])
    const compose = useComposeStore()
    compose.mode = 'CONTACT_TAGS'
    compose.recipientTagIds = [4, 5]
    compose.to = [' direct@example.com ', 'DIRECT@example.com']
    compose.cc = ['manager@example.com', 'direct@example.com']
    expect(compose.normalizedTo).toEqual(['direct@example.com'])
    expect(compose.normalizedCc).toEqual(['manager@example.com'])
    compose.setConfirmation({ confirmationId: 'c1', summary: [{ label: 'Recipients', value: '2' }], expiresAt: 'tomorrow' })
    expect(compose.confirmationSummary).toContain('Recipients: 2')
  })

  it('persists reusable draft fields without attachments or transient protocol state', () => {
    const compose = useComposeStore()
    compose.mode = 'CONTACT_TAGS'
    compose.recipientTagIds = [4]
    compose.subject = 'Quarterly update'
    compose.htmlText = '<p>Draft</p>'
    compose.plainText = 'Draft'
    compose.attachments = [{ id: 'f1', name: 'secret.pdf', kind: 'file', access: 'read', size: 1 }]
    compose.setConfirmation({ confirmationId: 'c1', summary: [], expiresAt: 'tomorrow' })
    compose.persistDraft()

    setActivePinia(createPinia())
    const restored = useComposeStore()
    restored.restoreDraft()

    expect(restored.subject).toBe('Quarterly update')
    expect(restored.htmlText).toBe('<p>Draft</p>')
    expect(restored.attachments).toEqual([])
    expect(restored.confirmation).toBeUndefined()
  })

  it('paginates archive results and tracks progress counters', () => {
    const archive = useArchiveStore()
    archive.offset = 50
    archive.limit = 25
    archive.nextPage()
    expect(archive.offset).toBe(75)
    archive.updateProgress({ processed: 3, successful: 2, failed: 1, newArchived: 2, duplicates: 1 })
    expect(archive.progress).toMatchObject({ processed: 3, failed: 1, duplicates: 1 })
  })

  it('applies live theme and locale environment updates', () => {
    applyEnvironment({ theme: 'dark', locale: 'zh-CN' })
    expect(readEnvironment()).toEqual({ theme: 'dark', locale: 'zh-CN' })
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.lang).toBe('zh-CN')
  })
})
