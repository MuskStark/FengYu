import { ConfirmDialog, useFengYuI18n } from '@infinia/plugin-ui'
import type { Confirmation, SummaryRow } from '../stores/compose'

export interface ConfirmationDialogProps {
  open: boolean
  confirmation?: Confirmation
  busy?: boolean
  onApprove: () => void
  onReject: () => void
}

/**
 * Confirmation-first send review on top of the kit's ConfirmDialog: the two
 * verbs are exactly "confirm send" / "reject", and the worker's summary rows
 * render as the dialog body (meta rows first, then per-message groups).
 */
export default function ConfirmationDialog({ open, confirmation, busy = false, onApprove, onReject }: ConfirmationDialogProps) {
  const { t } = useFengYuI18n()
  const rows: SummaryRow[] = confirmation?.summary ?? []
  // Rows whose `group` is empty are top-level meta (account/mode/messages/ignored/skipped); the rest
  // cluster by group so each message becomes one collapsible panel. Order follows backend emission.
  const metaRows = rows.filter(row => !row.group)
  const groups: Array<{ key: string; rows: SummaryRow[] }> = []
  for (const row of rows) {
    if (!row.group) continue
    const bucket = groups.find(group => group.key === row.group)
    if (bucket) bucket.rows.push(row)
    else groups.push({ key: row.group, rows: [row] })
  }

  /** Resolve a label key through i18n; fall back to the raw key if no translation is registered. */
  function labelOf(key: string): string {
    const path = `conf.${key}`
    const translated = t(path)
    return translated === path ? key : translated
  }
  /** Mode values (ATTACHMENT_TAGS, …) are data; map them to a human label when a translation exists. */
  function valueOf(row: SummaryRow): string {
    if (!row.value) return t('common.none')
    if (row.label === 'mode') {
      const path = `conf.mode_${row.value}`
      const translated = t(path)
      return translated === path ? row.value : translated
    }
    return row.value
  }
  function groupTitle(group: { key: string; rows: SummaryRow[] }): string {
    const toRow = group.rows.find(row => row.label === 'to')
    const count = toRow ? toRow.value.split(',').map(s => s.trim()).filter(Boolean).length : 0
    return count ? `${group.key} · ${t('confirmation.recipients', count)}` : group.key
  }

  return (
    <ConfirmDialog
      open={open}
      title={t('confirmation.title')}
      confirmLabel={t('confirmation.approve')}
      cancelLabel={t('confirmation.reject')}
      busy={busy}
      onConfirm={onApprove}
      onCancel={onReject}
      className="confirm-dialog max-w-[760px]"
    >
      <div className="confirm-body">
        {metaRows.length ? <h4 className="confirm-section-title">{t('confirmation.summary')}</h4> : null}
        {metaRows.length ? (
          <dl className="confirm-meta detail">
            {metaRows.map(row => (
              <div key={row.label} className="confirm-row">
                <dt>{labelOf(row.label)}</dt>
                <dd>{valueOf(row)}</dd>
              </div>
            ))}
          </dl>
        ) : null}
        {groups.length ? <h4 className="confirm-section-title">{t('confirmation.messages')}</h4> : null}
        {groups.map(group => (
          <details key={group.key} className="confirm-group">
            <summary>{groupTitle(group)}</summary>
            <dl className="confirm-fields detail">
              {group.rows.map(row => (
                <div key={row.label} className="confirm-row">
                  <dt>{labelOf(row.label)}</dt>
                  <dd>{valueOf(row)}</dd>
                </div>
              ))}
            </dl>
          </details>
        ))}
        <p className="confirm-expires">{t('confirmation.expires', confirmation?.expiresAt ?? '')}</p>
      </div>
    </ConfirmDialog>
  )
}
