import { useState } from 'react'
import type { FileRef } from '@infinia/plugin-sdk'
import { GhostButton, GoldButton, useFengYuI18n } from '@infinia/plugin-ui'
import { actionable, checked, files, rpc } from '../sdk'

export interface ImportContactsDialogProps {
  open: boolean
  onClose: () => void
  onImported: () => void
}

type ImportPreview = {
  rowsTotal: number; rowsValid: number; createdContacts: number; mergedContacts: number; skippedContacts: number
  createdTags: string[]; errors: { row: number; message: string }[]
}
type ImportResult = {
  created: number; merged: number; skipped: number; tagsCreated: number; tagsAssigned: number
  errors: { row: number; message: string }[]
}

const DUPLICATE_MODES: Array<'merge' | 'skip' | 'overwrite'> = ['merge', 'skip', 'overwrite']

/**
 * Two-step CSV/Excel import: pick a file + duplicate mode, preview the parse,
 * then commit. The commit forwards the same duplicate mode as the preview so
 * the two calls stay symmetric.
 */
export default function ImportContactsDialog({ open, onClose, onImported }: ImportContactsDialogProps) {
  const { t } = useFengYuI18n()
  const [fileRef, setFileRef] = useState<FileRef | null>(null)
  const [duplicateMode, setDuplicateMode] = useState<'merge' | 'skip' | 'overwrite'>('merge')
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')
  const [busy, setBusy] = useState(false)
  const [preview, setPreview] = useState<ImportPreview | null>(null)

  if (!open) return null

  function reset(): void {
    setFileRef(null)
    setDuplicateMode('merge')
    setError('')
    setInfo('')
    setPreview(null)
    setBusy(false)
  }

  function close(): void {
    reset()
    onClose()
  }

  /**
   * Generates and downloads a CSV template in the browser. The header uses the
   * import aliases the parser always recognizes, so the exported file imports
   * back unchanged. A leading UTF-8 BOM is prepended so Excel detects the encoding
   * and renders any later-added non-ASCII text correctly.
   */
  function downloadTemplate(): void {
    const rows = [
      'email,name,notes,tags',
      'alice@example.com,Alice Chen,Big client,Marketing|VIP',
      'bob@example.com,Bob Stone,,Sales;Priority',
    ]
    const csv = '\uFEFF' + rows.join('\r\n') + '\r\n'
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'contacts-template.csv'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  }

  async function chooseFile(): Promise<void> {
    try {
      setError('')
      const picked = await files.open()
      setFileRef(picked)
    } catch (value) {
      setError(actionable(value, t('contacts.importChooseFile')))
    }
  }

  async function runPreview(): Promise<void> {
    if (!fileRef) { setError(t('contacts.importNoFile')); return }
    setBusy(true); setError(''); setPreview(null)
    try {
      const result = await checked(rpc.email_contacts_import_preview({
        sourceFile: fileRef as unknown as string,
        duplicateMode,
      }))
      setPreview(result.preview)
    } catch (value) {
      setError(actionable(value, t('contacts.importPreviewAction')))
    } finally {
      setBusy(false)
    }
  }

  async function runCommit(): Promise<void> {
    if (!fileRef) return
    setBusy(true); setError('')
    try {
      const result = await checked(rpc.email_contacts_import_commit({
        sourceFile: fileRef as unknown as string,
        duplicateMode,
      }))
      const outcome = result.result
      setInfo(t('contacts.importDone', outcome.created, outcome.merged, outcome.skipped, outcome.tagsCreated))
      onImported()
      close()
    } catch (value) {
      setError(actionable(value, t('contacts.importAction')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="email-modal" role="dialog" aria-modal="true" aria-label={t('contacts.importTitle')}
      onClick={event => { if (event.target === event.currentTarget) close() }}>
      <section className="email-modal-card">
        <header className="email-modal-head">
          <h2>{t('contacts.importTitle')}</h2>
          <GhostButton data-testid="import-close" onClick={close} disabled={busy}>{t('common.close')}</GhostButton>
        </header>
        <div className="email-modal-body">
          {error ? <p className="email-alert email-alert--error" role="alert">{error}</p> : null}
          {info ? <p className="email-alert email-alert--success" role="status">{info}</p> : null}

          {!preview ? (
            <div>
              <p className="email-step-title">{t('contacts.importStep1')}</p>
              <div className="inline-fields">
                <GhostButton data-testid="import-choose-file" onClick={() => void chooseFile()}>{t('contacts.importChooseFile')}</GhostButton>
                <span className="email-hint">{fileRef?.name ?? t('contacts.importNoFile')}</span>
              </div>
              <p className="email-hint email-hint--caption">{t('contacts.importFileHint')}</p>
              <div className="inline-fields">
                <button type="button" className="email-text-button" data-testid="import-download-template" onClick={downloadTemplate}>
                  {t('contacts.importDownloadTemplate')}
                </button>
                <span className="email-hint email-hint--caption">{t('contacts.importTemplateHint')}</span>
              </div>
              <p className="email-step-title">{t('contacts.importDuplicates')}</p>
              <div className="import-modes" data-testid="import-duplicate-mode" role="radiogroup" aria-label={t('contacts.importDuplicates')}>
                {DUPLICATE_MODES.map(mode => (
                  <label key={mode} className="import-mode">
                    <input
                      type="radio"
                      name="email-import-duplicate-mode"
                      value={mode}
                      checked={duplicateMode === mode}
                      onChange={() => setDuplicateMode(mode)}
                      data-testid={`import-mode-${mode}`}
                    />
                    <span>{t(`contacts.import${mode === 'merge' ? 'Merge' : mode === 'skip' ? 'Skip' : 'Overwrite'}`)}</span>
                  </label>
                ))}
              </div>
            </div>
          ) : (
            <div data-testid="import-preview-step">
              <p className="email-step-title">{t('contacts.importStep2')}</p>
              <p className="email-alert email-alert--info" data-testid="import-parsed-summary">
                {t('contacts.importParsed', preview.rowsTotal, preview.rowsValid, preview.errors.length)}
              </p>
              <ul className="import-summary">
                <li data-testid="import-create-line">{t('contacts.importCreate', preview.createdContacts)}</li>
                <li data-testid="import-merge-line">{t('contacts.importMergeCount', preview.mergedContacts)}</li>
                <li data-testid="import-skip-line">{t('contacts.importSkipCount', preview.skippedContacts)}</li>
                {preview.createdTags.length ? (
                  <li data-testid="import-tags-line">{t('contacts.importTagsCreate', preview.createdTags.length, preview.createdTags.join(', '))}</li>
                ) : null}
              </ul>
              {preview.errors.length ? (
                <div className="import-errors">
                  <p className="email-step-title">{t('contacts.importErrors', preview.errors.length)}</p>
                  {preview.errors.map((item, index) => (
                    <p key={index} className="email-hint email-hint--caption">
                      {t('contacts.importErrors', preview.errors.length)} · {item.row}: {item.message}
                    </p>
                  ))}
                </div>
              ) : null}
            </div>
          )}
        </div>
        <footer className="email-modal-foot">
          {!preview ? (
            <>
              <GhostButton data-testid="import-cancel" disabled={busy} onClick={close}>{t('common.cancel')}</GhostButton>
              <GoldButton data-testid="import-preview-btn" disabled={!fileRef || busy} onClick={() => void runPreview()}>
                {busy ? t('common.loading') : t('contacts.importPreview')}
              </GoldButton>
            </>
          ) : (
            <>
              <GhostButton data-testid="import-back" disabled={busy} onClick={() => setPreview(null)}>{t('contacts.importBack')}</GhostButton>
              <GoldButton data-testid="import-confirm-btn" disabled={busy} onClick={() => void runCommit()}>
                {busy ? t('common.loading') : t('contacts.importConfirm')}
              </GoldButton>
            </>
          )}
        </footer>
      </section>
    </div>
  )
}
