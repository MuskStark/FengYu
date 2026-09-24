import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  finishDialog,
  isPromptView,
  subscribeDialogs,
  type DialogView,
} from '@/lib/appDialogs'
import '@/styles/dialog.css'

/**
 * The single renderer for the app-dialog store (lib/appDialogs): every
 * appConfirm/appPrompt call — directly or through platform.confirm — lands
 * here. Mounted once by the AppShell so dialogs work on every route.
 */
export default function AppDialogHost() {
  const { t } = useTranslation()
  const [dialog, setDialog] = useState<DialogView | null>(null)
  const [value, setValue] = useState('')
  const inputRef = useRef<HTMLInputElement | null>(null)
  const confirmRef = useRef<HTMLButtonElement | null>(null)
  const cancelRef = useRef<HTMLButtonElement | null>(null)

  useEffect(() => subscribeDialogs(setDialog), [])

  // Seed the prompt input per dialog and move focus: the text field for prompts,
  // and for confirms the safe end (destructive dialogs start on Cancel).
  useEffect(() => {
    if (!dialog) return
    setValue(isPromptView(dialog) ? (dialog.initial ?? '') : '')
    if (isPromptView(dialog)) inputRef.current?.focus()
    else if (dialog.danger) cancelRef.current?.focus()
    else confirmRef.current?.focus()
  }, [dialog])

  // ESC dismisses as cancel — it must not leak into the page underneath.
  useEffect(() => {
    if (!dialog) return
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        finishDialog(dialog.id, isPromptView(dialog) ? null : false)
      }
    }
    window.addEventListener('keydown', onKeydown, true)
    return () => window.removeEventListener('keydown', onKeydown, true)
  }, [dialog])

  if (!dialog) return null
  const isPrompt = isPromptView(dialog)
  const cancel = () => finishDialog(dialog.id, isPrompt ? null : false)
  const confirm = () => finishDialog(dialog.id, isPrompt ? value : true)

  return (
    <div className="app-dialog-backdrop" role="presentation" onClick={cancel}>
      <section
        className="app-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={dialog.title ?? dialog.message}
        onClick={(event) => event.stopPropagation()}
      >
        {dialog.title && <h2 className="app-dialog__title">{dialog.title}</h2>}
        <p className="app-dialog__message">{dialog.message}</p>
        {isPrompt && (
          <input
            ref={inputRef}
            className="app-dialog__input"
            type="text"
            value={value}
            placeholder={dialog.placeholder}
            onChange={(event) => setValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                confirm()
              }
            }}
          />
        )}
        <div className="app-dialog__actions">
          <button ref={cancelRef} className="cx-btn cx-btn--outline" onClick={cancel}>
            {dialog.cancelLabel ?? t('common.cancel')}
          </button>
          <button
            ref={confirmRef}
            className={`cx-btn ${dialog.danger ? 'cx-btn--danger' : 'cx-btn--primary'}`}
            onClick={confirm}
          >
            {dialog.confirmLabel ?? t('common.confirm')}
          </button>
        </div>
      </section>
    </div>
  )
}
