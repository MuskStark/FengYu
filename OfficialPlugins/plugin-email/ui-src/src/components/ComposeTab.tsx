import { useEffect, useMemo, useRef, useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import {
  CardSpotlight,
  Chip,
  ErrorState,
  GhostButton,
  GoldButton,
  Select,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { IconPaperclip, IconX } from '@tabler/icons-react'
import { useAccountsStore } from '../stores/accounts'
import { useComposeStore, selectNormalizedCc, selectNormalizedTo, normalizeAddresses, type ComposeMode } from '../stores/compose'
import { useContactsStore, selectRecipientPreview } from '../stores/contacts'
import { actionable, checked, files, rpc } from '../sdk'
import RichTextEditor from './RichTextEditor'
import ConfirmationDialog from './ConfirmationDialog'

/** Parse free-form address input (comma / semicolon / newline separated) into normalized chips. */
function parseAddresses(raw: string): string[] {
  return normalizeAddresses(raw.split(/[,;\n]/))
}

export default function ComposeTab() {
  const { t } = useFengYuI18n()
  const accounts = useAccountsStore(state => state.accounts)
  const selectedId = useAccountsStore(state => state.selectedId)
  const compose = useComposeStore(useShallow(state => ({
    mode: state.mode,
    recipientTagIds: state.recipientTagIds,
    to: state.to,
    cc: state.cc,
    subject: state.subject,
    plainText: state.plainText,
    htmlText: state.htmlText,
    attachments: state.attachments,
    sendResult: state.sendResult,
    draftSavedAt: state.draftSavedAt,
  })))
  const confirmation = useComposeStore(state => state.confirmation)
  const update = useComposeStore(state => state.update)
  const setConfirmation = useComposeStore(state => state.setConfirmation)
  const clearTransient = useComposeStore(state => state.clearTransient)
  const tags = useContactsStore(state => state.tags)
  const recipientPreview = useContactsStore(useShallow(selectRecipientPreview))
  const loadContacts = useContactsStore(state => state.load)

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [dialog, setDialog] = useState(false)
  // Restore the persisted draft once, before the controlled inputs initialize
  // (the previous tree ran the equivalent in component setup).
  useState(() => { useComposeStore.getState().restoreDraft() })
  const [toInput, setToInput] = useState(() => selectNormalizedTo(useComposeStore.getState()).join(', '))
  const [ccInput, setCcInput] = useState(() => selectNormalizedCc(useComposeStore.getState()).join(', '))

  const normalizedTo = useMemo(() => normalizeAddresses(compose.to), [compose.to])
  const normalizedCc = useMemo(() => selectNormalizedCc({ to: compose.to, cc: compose.cc }), [compose.to, compose.cc])

  // The "from" dropdown binds to accounts.accounts, which is populated by App's load(). If that
  // load failed (SDK handshake race, a transient error swallowed as a banner) this tab would render
  // an empty dropdown with no retry. Re-fetch on mount when the list is empty so the account picker
  // is always populated; load() is idempotent.
  useEffect(() => {
    if (!useAccountsStore.getState().accounts.length) {
      useAccountsStore.getState().load().catch(value => { setError(actionable(value, t('accounts.loading'))) })
    }
  }, [])  // mount-only: `t` is a fresh bound fn per render and must not be a dep

  const canReview = Boolean(selectedId)
    && (compose.mode === 'DIRECT' ? normalizedTo.length > 0 : compose.recipientTagIds.length > 0)
    && Boolean(compose.plainText.trim() || compose.htmlText.trim())
  const recipientHint = compose.mode === 'CONTACT_TAGS'
    ? t('compose.separateMessages', recipientPreview.length)
    : t('compose.directHint', normalizedTo.length)

  // Draft autosave (400ms debounce; like the previous watch(), not on mount).
  const draftSkipFirst = useRef(true)
  useEffect(() => {
    if (draftSkipFirst.current) { draftSkipFirst.current = false; return }
    const timer = window.setTimeout(() => useComposeStore.getState().persistDraft(), 400)
    return () => window.clearTimeout(timer)
  }, [compose.mode, compose.recipientTagIds, compose.to, compose.cc, compose.subject,
    compose.htmlText, compose.plainText])

  async function selectMode(mode: ComposeMode): Promise<void> {
    update({ mode })
    if (mode === 'CONTACT_TAGS' && !useContactsStore.getState().tags.length) {
      try { await loadContacts() } catch (value) { setError(actionable(value, t('compose.loadTags'))) }
    }
  }

  async function addAttachment(): Promise<void> {
    try {
      const value = await files.open()
      if (value) update({ attachments: [...useComposeStore.getState().attachments, value] })
    } catch (value) { setError(actionable(value, t('compose.selectAttachment'))) }
  }

  function removeAttachment(id: string): void {
    update({ attachments: useComposeStore.getState().attachments.filter(item => item.id !== id) })
  }

  async function prepare(): Promise<void> {
    setBusy(true); setError(''); clearTransient()
    try {
      const state = useComposeStore.getState()
      const recipients = state.mode === 'DIRECT'
        ? { to: selectNormalizedTo(state) }
        : { recipientTagIds: state.recipientTagIds }
      const result = await checked(rpc.email_send_single({
        accountId: selectedId!,
        ...recipients,
        cc: selectNormalizedCc(state),
        subject: state.subject,
        plainText: state.plainText,
        htmlText: state.htmlText,
        attachments: state.attachments as unknown as string[],
      }))
      setConfirmation(result.confirmation)
      setDialog(true)
    } catch (value) { setError(actionable(value, t('compose.prepareAction'))) }
    finally { setBusy(false) }
  }

  async function confirm(): Promise<void> {
    const current = useComposeStore.getState().confirmation
    if (!current) return
    setBusy(true)
    try {
      const result = await checked(rpc.confirm_send({
        confirmationId: current.confirmationId,
      }))
      update({ sendResult: result.send }); setDialog(false)
    } catch (value) { setError(actionable(value, t('compose.sendAction'))) }
    finally { setBusy(false) }
  }

  async function reject(): Promise<void> {
    const current = useComposeStore.getState().confirmation
    if (!current) return
    try {
      await checked(rpc.reject_send({ confirmationId: current.confirmationId }))
      setDialog(false)
    } catch (value) { setError(actionable(value, t('compose.cancelAction'))) }
  }

  return (
    <section className="workspace-grid">
      <article className="fy-card">
        <header className="fy-card-title"><h2>{t('compose.title')}</h2></header>
        <div className="fy-card-body">
          {error ? <ErrorState title={t('errors.unknown')} message={error} className="mb-4" /> : null}
          <label className="email-field">
            <span>{t('compose.from')}</span>
            <Select className="email-select" value={selectedId === null ? '' : String(selectedId)} data-testid="compose-account"
              onChange={value => useAccountsStore.getState().select(Number(value))}
              options={accounts.map(account => ({ value: String(account.id), label: account.email }))} />
          </label>
          <div className="mode-switch" role="group" aria-label={t('compose.recipientMode')}>
            <button type="button" data-testid="compose-mode-direct" className={'mode-chip-btn' + (compose.mode === 'DIRECT' ? ' mode-chip-btn--active' : '')} onClick={() => void selectMode('DIRECT')}>
              {t('compose.direct')}
            </button>
            <button type="button" data-testid="compose-mode-tags" className={'mode-chip-btn' + (compose.mode === 'CONTACT_TAGS' ? ' mode-chip-btn--active' : '')} onClick={() => void selectMode('CONTACT_TAGS')}>
              {t('compose.contactTags')}
            </button>
          </div>
          {compose.mode === 'DIRECT' ? (
            <label className="email-field">
              <span>{t('compose.to')}</span>
              <textarea className="email-textarea" rows={2} value={toInput} data-testid="compose-to"
                placeholder="a@example.com, b@example.com"
                onChange={event => { setToInput(event.target.value); update({ to: parseAddresses(event.target.value) }) }} />
            </label>
          ) : (
            <div className="email-field">
              <span>{t('compose.contactTags')}</span>
              <div className="tag-picker" role="group" aria-label={t('compose.contactTags')}>
                {tags.map(tag => {
                  const active = compose.recipientTagIds.includes(tag.id)
                  return (
                    <button key={tag.id} type="button" data-testid={`compose-tag-${tag.id}`}
                      className={'tag-chip' + (active ? ' tag-chip--active' : '')}
                      onClick={() => update({
                        recipientTagIds: active
                          ? compose.recipientTagIds.filter(id => id !== tag.id)
                          : [...compose.recipientTagIds, tag.id],
                      })}>
                      {tag.name}
                    </button>
                  )
                })}
                {!tags.length ? <span className="email-hint">{t('common.loading')}</span> : null}
              </div>
            </div>
          )}
          <p className="email-hint">{recipientHint}</p>
          <label className="email-field">
            <span>{t('compose.cc')}</span>
            <textarea className="email-textarea" rows={2} value={ccInput}
              onChange={event => { setCcInput(event.target.value); update({ cc: parseAddresses(event.target.value) }) }} />
          </label>
          <label className="email-field">
            <span>{t('compose.subject')}</span>
            <input className="email-input" type="text" value={compose.subject} onChange={event => update({ subject: event.target.value })} />
          </label>
          <RichTextEditor
            value={compose.htmlText}
            onChange={html => update({ htmlText: html })}
            onPlainText={plain => update({ plainText: plain })}
          />
          <div className="attachment-row">
            {compose.attachments.map(item => (
              <Chip key={item.id}>
                <IconPaperclip size={13} stroke={1.6} />
                {item.name}
                <button type="button" className="attachment-remove" aria-label={t('common.delete')} onClick={() => removeAttachment(item.id)}>
                  <IconX size={13} stroke={1.8} />
                </button>
              </Chip>
            ))}
            <GhostButton onClick={() => void addAttachment()}>
              <IconPaperclip size={14} stroke={1.6} />
              {t('compose.attach')}
            </GhostButton>
          </div>
          <p className="email-hint">{compose.draftSavedAt ? t('compose.draftSaved') : t('compose.draftPending')}</p>
        </div>
        <footer className="fy-card-foot">
          <span className="email-hint">{canReview ? t('compose.ready') : t('compose.validation')}</span>
          <GoldButton data-testid="compose-review" disabled={!canReview || busy} onClick={() => void prepare()}>
            {busy ? t('common.loading') : t('compose.review')}
          </GoldButton>
        </footer>
      </article>

      <CardSpotlight radius={320} color="rgba(234,176,75,0.15)"
        className="fy-card workspace-summary compose-preview-card">
        <header className="fy-card-title"><h2>{t('compose.previewTitle')}</h2></header>
        <div className="fy-card-body">
          {compose.sendResult ? (
            <p className={'email-alert ' + (compose.sendResult.failed ? 'email-alert--warning' : 'email-alert--success')}>
              {t('compose.sendResult', compose.sendResult.succeeded, compose.sendResult.failed)}
            </p>
          ) : null}
          <h3 className="preview-subject">{compose.subject || t('compose.noSubject')}</h3>
          <div className="email-preview" dangerouslySetInnerHTML={{ __html: compose.htmlText || `<p>${t('compose.previewEmpty')}</p>` }} />
        </div>
      </CardSpotlight>

      <ConfirmationDialog open={dialog} confirmation={confirmation} busy={busy}
        onApprove={() => void confirm()} onReject={() => void reject()} />
    </section>
  )
}
