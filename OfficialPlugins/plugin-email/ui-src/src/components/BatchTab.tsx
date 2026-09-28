import { useEffect, useRef, useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import {
  CardSpotlight,
  CardStack,
  Chip,
  ErrorState,
  GhostButton,
  GoldButton,
  Progress,
  Select,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { IconFolderOpen, IconPaperclip, IconSend, IconX } from '@tabler/icons-react'
import { useAccountsStore } from '../stores/accounts'
import { useBatchStore } from '../stores/batch'
import { useContactsStore } from '../stores/contacts'
import { actionable, checked, files, rpc } from '../sdk'
import RichTextEditor from './RichTextEditor'
import ConfirmationDialog from './ConfirmationDialog'

/** Snapshot of exactly what a preview/prepare call sends (fresh store state). */
function batchParams(): Parameters<typeof rpc.email_send_batch>[0] {
  const accounts = useAccountsStore.getState()
  const batch = useBatchStore.getState()
  return {
    accountId: accounts.selectedId!,
    recipientGroupTagIds: batch.recipientGroupTagIds,
    ccGroupTagIds: batch.ccGroupTagIds,
    inputDirectory: batch.inputDirectory as unknown as string,
    commonAttachments: batch.commonAttachments as unknown as string[],
    subject: batch.subject,
    plainText: batch.plainText,
    htmlText: batch.htmlText,
  }
}

export default function BatchTab() {
  const { t } = useFengYuI18n()
  const accounts = useAccountsStore(state => state.accounts)
  const selectedId = useAccountsStore(state => state.selectedId)
  const batch = useBatchStore(useShallow(state => ({
    inputDirectory: state.inputDirectory,
    recipientGroupTagIds: state.recipientGroupTagIds,
    ccGroupTagIds: state.ccGroupTagIds,
    commonAttachments: state.commonAttachments,
    subject: state.subject,
    plainText: state.plainText,
    htmlText: state.htmlText,
    preview: state.preview,
    sendResult: state.sendResult,
  })))
  const confirmation = useBatchStore(state => state.confirmation)
  const update = useBatchStore(state => state.update)
  const applyPreview = useBatchStore(state => state.applyPreview)
  const clearPreview = useBatchStore(state => state.clearPreview)
  const tags = useContactsStore(state => state.tags)
  const loadContacts = useContactsStore(state => state.load)

  const [busy, setBusy] = useState(false)
  const [previewBusy, setPreviewBusy] = useState(false)
  const [error, setError] = useState('')
  const [dialog, setDialog] = useState(false)
  const previewTimer = useRef<number | undefined>(undefined)

  const canPreview = Boolean(selectedId && batch.inputDirectory
    && batch.recipientGroupTagIds.length && (batch.plainText.trim() || batch.htmlText.trim()))
  const messageCount = batch.preview.messages.length

  useEffect(() => {
    if (!useContactsStore.getState().tags.length) {
      loadContacts().catch(value => { setError(actionable(value, t('batch.loadTags'))) })
    }
  }, [])  // mount-only: `t` is a fresh bound fn per render and must not be a dep

  // Auto-refresh the preview (450ms debounce) whenever a preview input changes
  // (not on mount — same semantics as the previous watch()).
  const previewSkipFirst = useRef(true)
  useEffect(() => {
    if (previewSkipFirst.current) { previewSkipFirst.current = false; return }
    window.clearTimeout(previewTimer.current)
    clearPreview()
    const canRun = Boolean(useAccountsStore.getState().selectedId && useBatchStore.getState().inputDirectory
      && useBatchStore.getState().recipientGroupTagIds.length
      && (useBatchStore.getState().plainText.trim() || useBatchStore.getState().htmlText.trim()))
    if (canRun) previewTimer.current = window.setTimeout(() => { void refreshPreview() }, 450)
    return () => window.clearTimeout(previewTimer.current)
  }, [selectedId, batch.inputDirectory, batch.recipientGroupTagIds, batch.ccGroupTagIds,
    batch.commonAttachments, batch.subject, batch.plainText, batch.htmlText])
  useEffect(() => () => window.clearTimeout(previewTimer.current), [])

  async function chooseDirectory(): Promise<void> {
    try { update({ inputDirectory: await files.inputDirectory() }) }
    catch (value) { setError(actionable(value, t('batch.selectDirectory'))) }
  }

  async function addCommonAttachment(): Promise<void> {
    try {
      const value = await files.open()
      if (value) update({ commonAttachments: [...useBatchStore.getState().commonAttachments, value] })
    } catch (value) { setError(actionable(value, t('batch.selectCommon'))) }
  }

  function removeCommonAttachment(id: string): void {
    update({ commonAttachments: useBatchStore.getState().commonAttachments.filter(item => item.id !== id) })
  }

  async function refreshPreview(): Promise<void> {
    if (!canPreview) return
    setPreviewBusy(true); setError('')
    try {
      const result = await checked(rpc.email_batch_preview(batchParams()))
      applyPreview(result.preview)
    } catch (value) { setError(actionable(value, t('batch.previewAction'))) }
    finally { setPreviewBusy(false) }
  }

  async function prepare(): Promise<void> {
    setBusy(true); setError('')
    try {
      const result = await checked(rpc.email_send_batch(batchParams()))
      update({ confirmation: result.confirmation }); setDialog(true)
    } catch (value) { setError(actionable(value, t('batch.prepareAction'))) }
    finally { setBusy(false) }
  }

  async function confirm(): Promise<void> {
    const current = useBatchStore.getState().confirmation
    if (!current) return
    setBusy(true)
    try {
      const result = await checked(rpc.confirm_send({
        confirmationId: current.confirmationId,
      }))
      update({ sendResult: result.send }); setDialog(false)
    } catch (value) { setError(actionable(value, t('batch.sendAction'))) }
    finally { setBusy(false) }
  }

  async function reject(): Promise<void> {
    const current = useBatchStore.getState().confirmation
    if (!current) return
    try {
      await checked(rpc.reject_send({ confirmationId: current.confirmationId }))
      setDialog(false)
    } catch (value) { setError(actionable(value, t('batch.cancelAction'))) }
  }

  function toggleTag(field: 'recipientGroupTagIds' | 'ccGroupTagIds', id: number): void {
    const current = useBatchStore.getState()[field]
    update({
      [field]: current.includes(id) ? current.filter(value => value !== id) : [...current, id],
    } as Partial<ReturnType<typeof useBatchStore.getState>>)
  }

  return (
    <section className="batch-workspace">
      <div className="batch-main">
        <article className="fy-card">
          <header className="fy-card-title"><h2>{t('batch.title')}</h2></header>
          <div className="fy-card-body">
            {error ? <ErrorState title={t('errors.unknown')} message={error} className="mb-4" /> : null}
            {batch.sendResult ? (
              <p className={'email-alert ' + (batch.sendResult.failed ? 'email-alert--warning' : 'email-alert--success')}>
                {t('compose.sendResult', batch.sendResult.succeeded, batch.sendResult.failed)}
              </p>
            ) : null}
            <div className="form-grid">
              <label className="email-field">
                <span>{t('compose.from')}</span>
                <Select className="email-select" value={selectedId === null ? '' : String(selectedId)}
                  onChange={value => useAccountsStore.getState().select(Number(value))}
                  options={accounts.map(account => ({ value: String(account.id), label: account.email }))} />
              </label>
              <div className="email-field">
                <span>{t('batch.directory')}</span>
                <div className="inline-fields">
                  <GhostButton data-testid="batch-directory" onClick={() => void chooseDirectory()}>
                    <IconFolderOpen size={14} stroke={1.6} />
                    {batch.inputDirectory?.name ?? t('batch.noDirectory')}
                  </GhostButton>
                </div>
              </div>
              <div className="email-field">
                <span>{t('batch.recipientGroups')}</span>
                <div className="tag-picker">
                  {tags.map(tag => (
                    <button key={tag.id} type="button" data-testid={`batch-recipient-tag-${tag.id}`}
                      className={'tag-chip' + (batch.recipientGroupTagIds.includes(tag.id) ? ' tag-chip--active' : '')}
                      onClick={() => toggleTag('recipientGroupTagIds', tag.id)}>
                      {tag.name}
                    </button>
                  ))}
                </div>
              </div>
              <div className="email-field">
                <span>{t('batch.ccGroups')}</span>
                <div className="tag-picker">
                  {tags.map(tag => (
                    <button key={tag.id} type="button"
                      className={'tag-chip' + (batch.ccGroupTagIds.includes(tag.id) ? ' tag-chip--active' : '')}
                      onClick={() => toggleTag('ccGroupTagIds', tag.id)}>
                      {tag.name}
                    </button>
                  ))}
                </div>
              </div>
            </div>
            <p className="email-alert email-alert--info">{t('batch.formula')}</p>
            <label className="email-field">
              <span>{t('compose.subject')}</span>
              <input className="email-input" type="text" value={batch.subject} onChange={event => update({ subject: event.target.value })} />
            </label>
            <RichTextEditor
              value={batch.htmlText}
              onChange={html => update({ htmlText: html })}
              onPlainText={plain => update({ plainText: plain })}
            />
            <div className="attachment-row">
              {batch.commonAttachments.map(item => (
                <Chip key={item.id}>
                  <IconPaperclip size={13} stroke={1.6} />
                  {item.name}
                  <button type="button" className="attachment-remove" aria-label={t('common.delete')} onClick={() => removeCommonAttachment(item.id)}>
                    <IconX size={13} stroke={1.8} />
                  </button>
                </Chip>
              ))}
              <GhostButton data-testid="batch-common-attachment" onClick={() => void addCommonAttachment()}>
                <IconPaperclip size={14} stroke={1.6} />
                {t('batch.commonAttachments')}
              </GhostButton>
            </div>
          </div>
          <footer className="fy-card-foot">
            <GhostButton data-testid="batch-refresh" disabled={!canPreview || previewBusy} onClick={() => void refreshPreview()}>
              {previewBusy ? t('common.loading') : t('batch.refresh')}
            </GhostButton>
            <GoldButton data-testid="batch-review" disabled={!messageCount || busy} onClick={() => void prepare()}>
              <IconSend size={14} stroke={1.7} />
              {busy ? t('common.loading') : t('batch.review', messageCount)}
            </GoldButton>
          </footer>
        </article>

        <article className="fy-card records-card-gap">
          <header className="fy-card-title"><h2>{t('batch.preview')}</h2></header>
          <div className="fy-card-body">
            {previewBusy ? <Progress status="indeterminate" label={t('batch.previewAction')} className="mb-4" /> : null}
            {messageCount ? (
              <div className="batch-preview-list">
                {batch.preview.messages.map((message, index) => (
                  <CardSpotlight key={index} radius={240} color="rgba(234,176,75,0.10)" className="batch-preview-item">
                    <h3 className="batch-preview-tag">{message.attachmentTag}</h3>
                    <p><strong>{t('compose.to')}:</strong> {message.to.join(', ')}</p>
                    <p><strong>{t('compose.cc')}:</strong> {message.cc.join(', ') || t('common.none')}</p>
                    <p><strong>{t('batch.tagAttachments')}:</strong> {message.tagAttachments.join(', ')}</p>
                    <p><strong>{t('batch.commonAttachments')}:</strong> {message.commonAttachments.join(', ') || t('common.none')}</p>
                  </CardSpotlight>
                ))}
              </div>
            ) : <p className="email-hint">{t('batch.previewEmpty')}</p>}
            {batch.preview.ignoredFiles.length ? (
              <p className="email-alert email-alert--info">{t('batch.ignoredFiles')}: {batch.preview.ignoredFiles.join(', ')}</p>
            ) : null}
            {batch.preview.skippedTags.length ? (
              <p className="email-alert email-alert--warning">
                {t('batch.skippedTags')}: {batch.preview.skippedTags.map(item => `${item.attachmentTag} (${item.reason})`).join(', ')}
              </p>
            ) : null}
          </div>
        </article>
      </div>

      <aside className="email-rail">
        <div className="fy-card email-rail-card">
          <div className="email-rail-head">
            <span className="email-rail-title">{t('batch.preview')}</span>
            <span className="email-rail-count">{messageCount}</span>
          </div>
          <div className="email-cardstack">
            {messageCount ? (
              <CardStack
                offset={9}
                scaleFactor={0.05}
                items={batch.preview.messages.slice(0, 5).map((message, index) => ({
                  id: index + 1,
                  name: message.attachmentTag ?? `#${index + 1}`,
                  designation: `${message.to.length} · ${message.tagAttachments.length + message.commonAttachments.length}`,
                  content: (
                    <div>
                      <p className="cardstack-line">{message.to.slice(0, 3).join(', ')}{message.to.length > 3 ? ` +${message.to.length - 3}` : ''}</p>
                      <p className="cardstack-line cardstack-line--muted">{message.tagAttachments.concat(message.commonAttachments).slice(0, 3).join(', ')}</p>
                    </div>
                  ),
                }))}
              />
            ) : (
              <p className="email-hint">{t('batch.previewEmpty')}</p>
            )}
          </div>
        </div>
        <div className="fy-card email-stat-card">
          <div className="email-stat-label">{t('batch.recipientGroups')}</div>
          <div className="email-stat-value">{batch.recipientGroupTagIds.length}</div>
          <div className="email-stat-sub email-hint email-hint--caption">{t('batch.ccGroups')}: {batch.ccGroupTagIds.length}</div>
        </div>
      </aside>

      <ConfirmationDialog open={dialog} confirmation={confirmation} busy={busy}
        onApprove={() => void confirm()} onReject={() => void reject()} />
    </section>
  )
}
