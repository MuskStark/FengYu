import { useEffect, useMemo, useState } from 'react'
import {
  Chip,
  ConfirmDialog,
  ErrorState,
  GhostButton,
  GoldButton,
  HexMark,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { IconTrash } from '@tabler/icons-react'
import { useContactsStore, type Contact } from '../stores/contacts'
import { actionable, checked, rpc } from '../sdk'
import ImportContactsDialog from './ImportContactsDialog'

export default function AddressBookTab() {
  const { t } = useFengYuI18n()
  const contacts = useContactsStore(state => state.contacts)
  const tags = useContactsStore(state => state.tags)
  const storeQuery = useContactsStore(state => state.query)
  const selectedTagIds = useContactsStore(state => state.selectedTagIds)
  const update = useContactsStore(state => state.update)
  const load = useContactsStore(state => state.load)

  const [contactId, setContactId] = useState<number | undefined>()
  const [email, setEmail] = useState('')
  const [nickname, setNickname] = useState('')
  const [notes, setNotes] = useState('')
  const [tagName, setTagName] = useState('')
  const [contactTagIds, setContactTagIds] = useState<number[]>([])
  const [selectedContacts, setSelectedContacts] = useState<number[]>([])
  const [assignTagIds, setAssignTagIds] = useState<number[]>([])
  const [error, setError] = useState('')
  const [tagQuery, setTagQuery] = useState('')
  const [showImport, setShowImport] = useState(false)
  const [pendingDelete, setPendingDelete] = useState<{ kind: 'contact' | 'tag'; id: number }>()

  const filteredTags = useMemo(() => {
    const q = tagQuery.trim().toLowerCase()
    return q ? tags.filter(tag => tag.name.toLowerCase().includes(q)) : tags
  }, [tags, tagQuery])
  const deleteMessage = pendingDelete?.kind === 'tag' ? t('contacts.tagDeleteConfirm') : t('contacts.deleteConfirm')
  const deleteTitle = pendingDelete?.kind === 'tag' ? t('contacts.tagDeleteAction') : t('contacts.deleteAction')

  useEffect(() => { load().catch(value => { setError(actionable(value, t('contacts.loadAction'))) }) }, [])

  function edit(item: Contact) {
    setContactId(item.id); setEmail(item.email); setNickname(item.nickname ?? '')
    setNotes(item.notes ?? ''); setContactTagIds([...(item.tagIds ?? [])])
  }
  function reset() { setContactId(undefined); setEmail(''); setNickname(''); setNotes(''); setContactTagIds([]) }
  const initials = (item: Contact) => (item.nickname || item.email || '?').charAt(0)
  function tagLabel(id: number): string { return tags.find(tag => tag.id === id)?.name ?? '' }
  function visibleTags(item: Contact): number[] { return (item.tagIds ?? []).slice(0, 2) }
  function hiddenTagCount(item: Contact): number { return Math.max(0, (item.tagIds ?? []).length - 2) }

  async function run(action: string, task: () => Promise<unknown>) {
    try { setError(''); await task(); await load() } catch (value) { setError(actionable(value, action)) }
  }

  const saveContact = () => run(t('contacts.saveAction'), async () => {
    await checked(rpc.email_contact_save({
      id: contactId, email, nickname, notes, tagIds: [...contactTagIds],
    }))
    reset()
  })
  const deleteContact = (id: number) => { setPendingDelete({ kind: 'contact', id }) }
  const addTag = () => run(t('contacts.tagSaveAction'), async () => { await checked(rpc.email_tag_save({ name: tagName })); setTagName('') })
  const deleteTag = (id: number) => { setPendingDelete({ kind: 'tag', id }) }
  const assign = () => run(t('contacts.assignAction'), () => checked(rpc.email_tags_assign({ contactIds: selectedContacts, tagIds: assignTagIds })))

  function confirmDelete(): void {
    const target = pendingDelete
    setPendingDelete(undefined)
    if (!target) return
    if (target.kind === 'contact') void run(t('contacts.deleteAction'), () => checked(rpc.email_contact_delete({ id: target.id })))
    else void run(t('contacts.tagDeleteAction'), () => checked(rpc.email_tag_delete({ id: target.id })))
  }

  function toggleIn(list: number[], setList: (next: number[]) => void, id: number): void {
    setList(list.includes(id) ? list.filter(value => value !== id) : [...list, id])
  }

  return (
    <section className="panel-grid contact-layout">
      <article className="fy-card contact-list-card">
        <header className="fy-card-title"><h2>{t('contacts.title')}</h2></header>
        <div className="fy-card-body">
          {error ? <ErrorState title={t('errors.unknown')} message={error} className="mb-4" /> : null}
          <div className="inline-fields">
            <input className="email-input" data-testid="contact-search" placeholder={t('common.search')}
              value={storeQuery} onChange={event => update({ query: event.target.value })}
              onKeyDown={event => { if (event.key === 'Enter') void load() }} />
            <div className="tag-picker">
              {tags.map(tag => (
                <button key={tag.id} type="button" className={'tag-chip' + (selectedTagIds.includes(tag.id) ? ' tag-chip--active' : '')}
                  onClick={() => toggleIn(selectedTagIds, next => update({ selectedTagIds: next }), tag.id)}>
                  {tag.name}
                </button>
              ))}
            </div>
            <GhostButton onClick={() => void load()}>{t('common.search')}</GhostButton>
          </div>
          <div>
            <GhostButton data-testid="contact-import" onClick={() => setShowImport(true)}>{t('contacts.importButton')}</GhostButton>
          </div>
          <div className="contact-list-scroll" data-testid="contact-list-scroll">
            {contacts.map(item => (
              <div key={item.id} className="contact-row" data-testid="contact-row" onClick={() => edit(item)}>
                <input type="checkbox" aria-label={item.email}
                  checked={selectedContacts.includes(item.id)}
                  onClick={event => event.stopPropagation()}
                  onChange={() => toggleIn(selectedContacts, setSelectedContacts, item.id)} />
                <HexMark size={34} className="email-hex-initial-host"><span className="email-hex-initial">{initials(item)}</span></HexMark>
                <div className="contact-row__main">
                  <div className="contact-row__name">{item.nickname || item.email}</div>
                  <div className="contact-row__email">{item.email}</div>
                  {(item.tagIds ?? []).length ? (
                    <div className="tag-overflow">
                      {visibleTags(item).map(id => <Chip key={id} className="tag-pill">{tagLabel(id)}</Chip>)}
                      {hiddenTagCount(item) > 0 ? (
                        <span title={(item.tagIds ?? []).slice(2).map(tagLabel).join(', ')}>
                          <Chip className="tag-pill">{t('contacts.tagsMore', hiddenTagCount(item))}</Chip>
                        </span>
                      ) : null}
                    </div>
                  ) : null}
                </div>
                <button type="button" className="email-danger-button" onClick={event => { event.stopPropagation(); deleteContact(item.id) }}>
                  <IconTrash size={14} stroke={1.6} />
                  {t('common.delete')}
                </button>
              </div>
            ))}
          </div>
          <div data-testid="contact-bulk-tags" className="inline-fields">
            <div className="tag-picker">
              {tags.map(tag => (
                <button key={tag.id} type="button" className={'tag-chip' + (assignTagIds.includes(tag.id) ? ' tag-chip--active' : '')}
                  onClick={() => toggleIn(assignTagIds, setAssignTagIds, tag.id)}>
                  {tag.name}
                </button>
              ))}
            </div>
            <GhostButton disabled={!selectedContacts.length} onClick={() => void assign()}>{t('contacts.assignTags')}</GhostButton>
          </div>
        </div>
      </article>

      <div className="contact-side-column">
        <article className="fy-card">
          <header className="fy-card-title"><h2>{contactId ? t('contacts.editContact') : t('contacts.newContact')}</h2></header>
          <div className="fy-card-body">
            <label className="email-field">
              <span>{t('contacts.email')}</span>
              <input className="email-input" data-testid="contact-email" value={email} onChange={event => setEmail(event.target.value)} />
            </label>
            <label className="email-field">
              <span>{t('contacts.name')}</span>
              <input className="email-input" value={nickname} onChange={event => setNickname(event.target.value)} />
            </label>
            <div className="email-field">
              <span>{t('contacts.assignTags')}</span>
              <div className="tag-picker" data-testid="contact-tags">
                {tags.map(tag => (
                  <button key={tag.id} type="button" data-testid={`contact-tag-${tag.id}`}
                    className={'tag-chip' + (contactTagIds.includes(tag.id) ? ' tag-chip--active' : '')}
                    onClick={() => toggleIn(contactTagIds, setContactTagIds, tag.id)}>
                    {tag.name}
                  </button>
                ))}
              </div>
            </div>
            <label className="email-field">
              <span>{t('contacts.notes')}</span>
              <textarea className="email-textarea" rows={2} data-testid="contact-notes" value={notes} onChange={event => setNotes(event.target.value)} />
            </label>
          </div>
          <footer className="fy-card-foot">
            {contactId ? <GhostButton onClick={reset}>{t('contacts.newContact')}</GhostButton> : null}
            <GoldButton data-testid="contact-save" onClick={() => void saveContact()}>{t('common.save')}</GoldButton>
          </footer>
        </article>

        <article className="fy-card" data-testid="tag-manager-card">
          <header className="fy-card-title"><h2>{t('contacts.manageTags')}</h2></header>
          <div className="fy-card-body">
            <input className="email-input" data-testid="tag-search" placeholder={t('contacts.tagSearch')}
              value={tagQuery} onChange={event => setTagQuery(event.target.value)} />
            <div className="tag-manager-list">
              {filteredTags.map(tag => (
                <div key={tag.id} className="tag-manager-row" data-testid="tag-manager-row">
                  <span>{tag.name}</span>
                  <button type="button" className="email-danger-button" onClick={() => deleteTag(tag.id)}>
                    <IconTrash size={13} stroke={1.6} />
                    {t('common.delete')}
                  </button>
                </div>
              ))}
            </div>
            <div className="inline-fields">
              <input className="email-input" placeholder={t('contacts.newTag')} value={tagName} onChange={event => setTagName(event.target.value)} />
              <GhostButton onClick={() => void addTag()}>{t('common.add')}</GhostButton>
            </div>
          </div>
        </article>
      </div>

      <ConfirmDialog
        open={Boolean(pendingDelete)}
        title={deleteTitle}
        message={deleteMessage}
        confirmLabel={t('common.delete')}
        destructive
        onConfirm={confirmDelete}
        onCancel={() => setPendingDelete(undefined)}
      />
      <ImportContactsDialog open={showImport} onClose={() => setShowImport(false)} onImported={() => void load()} />
    </section>
  )
}
