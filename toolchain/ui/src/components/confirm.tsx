import { useEffect, useRef, type ReactNode } from 'react'
import { cn } from '../lib/utils'
import { GhostButton } from './chrome'

/**
 * Confirmation-first dialog. Two verbs only (confirm / cancel); destructive
 * confirms render the danger tone. Focus starts on the confirm button and
 * Escape cancels.
 */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = '确认',
  cancelLabel = '取消',
  destructive = false,
  busy = false,
  onConfirm,
  onCancel,
  children,
  className,
}: {
  open: boolean
  title: ReactNode
  message?: ReactNode
  confirmLabel?: ReactNode
  cancelLabel?: ReactNode
  destructive?: boolean
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
  children?: ReactNode
  className?: string
}) {
  const confirmRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (open) confirmRef.current?.focus()
  }, [open])
  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onCancel])

  if (!open) return null
  return (
    <div
      data-confirm-dialog=""
      role="alertdialog"
      aria-modal="true"
      aria-label={typeof title === 'string' ? title : undefined}
      className="fixed inset-0 z-40 grid place-items-center bg-black/25 p-6"
      onClick={(event) => {
        if (event.target === event.currentTarget) onCancel()
      }}
    >
      <div
        className={cn('w-full max-w-[420px] rounded-xl border border-line bg-panel p-5 shadow-[0_18px_44px_rgba(24,24,27,0.18)]', className)}
      >
        <h2 className="text-[15px] font-semibold tracking-tight">{title}</h2>
        {message ? <p className="mt-1.5 text-[13.5px] leading-relaxed text-ink-2">{message}</p> : null}
        {children ? <div className="mt-3">{children}</div> : null}
        <div className="mt-5 flex justify-end gap-2">
          <GhostButton data-action="cancel" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </GhostButton>
          <button
            ref={confirmRef}
            type="button"
            data-action="confirm"
            disabled={busy}
            onClick={onConfirm}
            className={cn(
              'inline-flex h-8 items-center rounded-lg px-3.5 text-[13px] font-medium transition-transform active:translate-y-px disabled:opacity-50',
            )}
            style={
              destructive
                ? { background: 'var(--c-danger)', color: '#fff' }
                : { background: 'var(--c-gold)', color: 'var(--c-gold-ink)' }
            }
          >
            {busy ? '…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
