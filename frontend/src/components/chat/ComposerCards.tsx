/**
 * Leaf cards and helpers extracted verbatim from ChatComposer (behavior/style-neutral
 * split): menu item row, attachment chip icon, the tool-confirmation and ask_user
 * question cards, and the queued-send chip row. Each is prop-driven and store-bound —
 * no composer closure state.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Clock, FileImage, FileText, Folder, HelpCircle, Pencil, Shield, ShieldAlert, X } from 'lucide-react'
import { confirmationRetryable, dismissConfirmation, retryConfirmation } from '@/lib/aiConfirmation'
import { dismissQuestion, questionAnswerable, questionRetryable, retryQuestion } from '@/lib/aiQuestion'
import { cn } from '@/lib/utils'
import { useAiSessionStore } from '@/stores/aiSession'

export function MenuItem({ icon, title, hint, onClick }: { icon: React.ReactNode; title: string; hint: string; onClick: () => void }) {
  return (
    <button className="cx-btn cx-btn--text composer-menu__item" onClick={onClick}>
      {icon}
      <span className="grid text-left">
        <span>{title}</span>
        <span className="cx-muted text-ui-sm">{hint}</span>
      </span>
    </button>
  )
}

/** Chip icon for a draft attachment (MentionPanel's file heuristics: dir → image → file). */
export function attachmentIcon(attachment: import('@/services/types').DraftAttachment) {
  if (attachment.kind === 'directory') return <Folder size={16} />
  if (attachment.kind === 'image') return <FileImage size={16} />
  const ext = attachment.name.split('.').pop()?.toLowerCase() ?? ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'pdf'].includes(ext)) return <FileImage size={16} />
  return <FileText size={16} />
}

export function ConfirmationCard({ item }: { item: import('@/lib/aiConfirmation').ToolConfirmation }) {
  const { t } = useTranslation()
  const resolve = useAiSessionStore(state => state.resolveConfirmation)
  const status = item.status
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [always, setAlways] = useState(false)
  /** Error-card actions mutate the item in place (store pattern) — republish after. */
  const republish = () =>
    useAiSessionStore.setState(state => ({ conversations: [...state.conversations] }))
  return (
    <div className="composer-confirmation">
      <div className="composer-confirmation__title">
        {item.sandboxEscape ? <ShieldAlert size={14} /> : <Shield size={14} />}
        <span>{item.sandboxEscape ? t('aichat.confirmEscapeTitle') : t('aichat.confirmTitle')}</span>
      </div>
      {item.summary.map(row => (
        <div key={row.label} className="composer-confirmation__row">
          <span className="cx-muted composer-confirmation__label">{row.label}</span>
          <code className="composer-confirmation__value">{row.value}</code>
        </div>
      ))}
      {status === 'pending' && (
        <>
          <div className="composer-confirmation__actions">
            <button
              className="cx-btn cx-btn--primary cx-btn--sm"
              onClick={() => void resolve(item, true, { always })}
            >
              {always ? t('aichat.approveAlways') : t('aichat.approveOnce')}
            </button>
            <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => void resolve(item, false)}>
              {t('aichat.rejectSend')}
            </button>
            <button
              className="cx-btn cx-btn--text cx-btn--sm"
              onClick={() => setFeedbackOpen(open => !open)}
            >
              {t('aichat.rejectWithFeedback')}
            </button>
          </div>
          {/* "Always this conversation" only exists on host-approval cards — the plugin
              confirmation channel has no session-grant semantics to wire it to — and never
              for sandbox escapes (a per-conversation unfenced grant is too broad). */}
          {item.source === 'host' && !item.sandboxEscape && (
            <label className="composer-confirmation__always">
              <input type="checkbox" checked={always} onChange={event => setAlways(event.target.checked)} />
              {/* Honest labels: only workspace_exec approvals amend a PERSISTENT exec-policy
                  prefix rule (ChatToolApprovalGate.amendTokensOf) — everything else grants
                  for this conversation only. */}
              <span className="cx-muted">
                {item.toolName === 'workspace_exec'
                  ? t('aichat.alwaysAndRememberPrefix')
                  : t('aichat.alwaysThisConversation')}
              </span>
            </label>
          )}
          {feedbackOpen && (
            <div className="composer-confirmation__feedback">
              <textarea
                className="composer-confirmation__feedback-input"
                value={feedback}
                placeholder={t('aichat.feedbackPlaceholder')}
                rows={2}
                onChange={event => setFeedback(event.target.value)}
              />
              <button
                className="cx-btn cx-btn--text cx-btn--sm"
                disabled={!feedback.trim()}
                onClick={() => void resolve(item, false, { feedback: feedback.trim() })}
              >
                {t('aichat.rejectWithFeedbackSend')}
              </button>
            </div>
          )}
        </>
      )}
      {status === 'submitting' && <div className="cx-muted"><span className="cx-spin" /> {t('aichat.submittingApproval')}</div>}
      {status === 'error' && (
        <div className="cx-alert cx-alert--error">
          <span className="cx-alert__body">{item.error}</span>
          {/* A failed resolve is recoverable while the gate is still open; a dismiss
              retires the card so one bad approval never pins the composer forever. */}
          {confirmationRetryable(item) && (
            <button
              className="cx-btn cx-btn--text cx-btn--sm"
              onClick={() => { if (retryConfirmation(item)) republish() }}
            >
              {t('common.retry')}
            </button>
          )}
          <button
            className="cx-btn cx-btn--text cx-btn--sm"
            onClick={() => { if (dismissConfirmation(item)) republish() }}
          >
            {t('common.dismiss')}
          </button>
        </div>
      )}
    </div>
  )
}

/** An ask_user question card: the model's structured questions with option chips. */
export function QuestionCard({ item }: { item: import('@/lib/aiQuestion').QuestionCardState }) {
  const { t } = useTranslation()
  const submit = useAiSessionStore(state => state.submitQuestion)
  /** Selection/error actions mutate the card in place (store pattern) — republish after. */
  const republish = () =>
    useAiSessionStore.setState(state => ({ conversations: [...state.conversations] }))
  const toggle = (index: number, label: string) => {
    const current = item.selected[index] ?? []
    if (item.items[index]?.multiSelect) {
      item.selected[index] = current.includes(label)
        ? current.filter(value => value !== label)
        : [...current, label]
    } else {
      item.selected[index] = current.includes(label) ? [] : [label]
    }
    republish()
  }
  const answerable = questionAnswerable(item)
  return (
    <div className="composer-confirmation">
      <div className="composer-confirmation__title">
        <HelpCircle size={14} />
        <span>{t('aichat.questionTitle')}</span>
      </div>
      {item.items.map((question, index) => (
        <div key={index} className="composer-question">
          {question.header && (
            <span className="cx-muted composer-question__header">{question.header}</span>
          )}
          <div className="composer-question__text">{question.question}</div>
          <div className="composer-question__options">
            {question.options.map((option, optionIndex) => {
              const selected = (item.selected[index] ?? []).includes(option.label)
              return (
                <button
                  key={`${index}:${optionIndex}`}
                  type="button"
                  title={option.description}
                  aria-pressed={selected}
                  className={cn('composer-question__option', selected && 'composer-question__option--selected')}
                  onClick={() => toggle(index, option.label)}
                >
                  {option.label}
                </button>
              )
            })}
          </div>
          <input
            className="composer-question__other"
            type="text"
            placeholder={t('aichat.questionOther')}
            value={item.other[index] ?? ''}
            onChange={event => {
              item.other[index] = event.target.value
              republish()
            }}
          />
        </div>
      ))}
      {item.status === 'pending' && (
        <div className="composer-confirmation__actions">
          <button
            className="cx-btn cx-btn--primary cx-btn--sm"
            disabled={!answerable}
            onClick={() => void submit(item)}
          >
            {t('aichat.questionSubmit')}
          </button>
        </div>
      )}
      {item.status === 'submitting' && <div className="cx-muted"><span className="cx-spin" /> {t('aichat.submittingApproval')}</div>}
      {item.status === 'error' && (
        <div className="cx-alert cx-alert--error">
          <span className="cx-alert__body">{item.error}</span>
          {/* Retry only while the gate is still open; dismiss retires the card so a
              failed submit never leaves an unanswerable error pinned to the composer. */}
          {questionRetryable(item) && (
            <button
              className="cx-btn cx-btn--text cx-btn--sm"
              onClick={() => { if (retryQuestion(item)) republish() }}
            >
              {t('common.retry')}
            </button>
          )}
          <button
            className="cx-btn cx-btn--text cx-btn--sm"
            onClick={() => { if (dismissQuestion(item)) republish() }}
          >
            {t('common.dismiss')}
          </button>
        </div>
      )}
    </div>
  )
}

/** One queued send: click-to-edit prompt text (parked entries re-queue locally on save). */
export function QueueRow({ item, onRemove, onEdit }: {
  item: import('@/stores/aiSession').QueuedSend
  onRemove: () => void
  onEdit: (text: string) => void
}) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(item.prompt)
  if (editing) {
    return (
      <div className="composer-queue__row composer-queue__row--editing">
        <Clock size={12} />
        <input
          className="composer-queue__input"
          value={text}
          autoFocus
          onChange={event => setText(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter') { setEditing(false); onEdit(text) }
            if (event.key === 'Escape') { setText(item.prompt); setEditing(false) }
          }}
        />
        <button
          className="composer-queue__remove"
          title={t('common.confirm')}
          onClick={() => { setEditing(false); onEdit(text) }}
        ><Check size={12} /></button>
        <button
          className="composer-queue__remove"
          title={t('common.cancel')}
          onClick={() => { setText(item.prompt); setEditing(false) }}
        ><X size={12} /></button>
      </div>
    )
  }
  return (
    <div className="composer-queue__row">
      <Clock size={12} />
      <span className="composer-queue__text">{item.prompt}</span>
      <button
        className="composer-queue__remove"
        title={t('aichat.queueEdit')}
        onClick={() => setEditing(true)}
      ><Pencil size={12} /></button>
      <button
        className="composer-queue__remove"
        title={t('aichat.queueRemove')}
        onClick={onRemove}
      ><X size={12} /></button>
    </div>
  )
}

// ── inline-image helpers ────────────────────────────────────────────────────────

const MAX_INLINE_IMAGE_BYTES = 4 * 1024 * 1024

/** Reads clipboard/drag image Files into base64 inline-image records (bounded). */
export async function readImageFiles(files: File[]): Promise<Array<{ name: string; mimeType: string; base64Data: string }>> {
  const out: Array<{ name: string; mimeType: string; base64Data: string }> = []
  for (const file of files.slice(0, 4)) {
    if (file.size > MAX_INLINE_IMAGE_BYTES) continue
    try {
      const base64Data = await readFileAsBase64(file)
      out.push({ name: file.name || 'image', mimeType: file.type || 'image/png', base64Data })
    } catch {
      /* unreadable file drops out */
    }
  }
  return out
}


function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = String(reader.result ?? '')
      const comma = result.indexOf(',')
      resolve(comma >= 0 ? result.slice(comma + 1) : result)
    }
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}
