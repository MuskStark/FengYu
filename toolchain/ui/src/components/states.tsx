import type { ReactNode } from 'react'
import { cn } from '../lib/utils'

/** Skeleton rows shaped like the content they stand in for (no spinner clichés). */
export function LoadingState({ label, rows = 3, className }: { label?: ReactNode; rows?: number; className?: string }) {
  return (
    <div data-loading-state="" role="status" aria-live="polite" className={cn('grid gap-3 py-8', className)}>
      {label ? <div className="text-[13px] text-ink-2">{label}</div> : null}
      {Array.from({ length: rows }, (_, index) => (
        <div
          key={index}
          className="h-9 animate-pulse rounded-lg bg-muted-surface"
          style={{ width: `${100 - index * 12}%` }}
        />
      ))}
    </div>
  )
}

/** Composed empty state: icon, one line, one way out. */
export function EmptyState({
  icon,
  title,
  message,
  action,
  className,
}: {
  icon?: ReactNode
  title: ReactNode
  message?: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    <div
      data-empty-state=""
      className={cn('grid place-items-center gap-2 py-12 text-center', className)}
    >
      {icon ? (
        <span className="grid size-11 place-items-center rounded-xl bg-muted-surface text-ink-3">{icon}</span>
      ) : null}
      <div className="text-[14px] font-medium text-ink">{title}</div>
      {message ? <div className="max-w-[46ch] text-[13px] leading-relaxed text-ink-2">{message}</div> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  )
}

/** Inline error state with a retry affordance. */
export function ErrorState({
  title,
  message,
  onRetry,
  retryLabel = '重试',
  className,
}: {
  title: ReactNode
  message?: ReactNode
  onRetry?: () => void
  retryLabel?: ReactNode
  className?: string
}) {
  return (
    <div
      data-error-state=""
      role="alert"
      className={cn('rounded-xl border px-4 py-3.5', className)}
      style={{ borderColor: 'var(--c-danger)', background: 'var(--c-danger-bg)' }}
    >
      <div className="text-[13.5px] font-medium" style={{ color: 'var(--c-danger)' }}>
        {title}
      </div>
      {message ? <div className="mt-1 break-words text-[13px] text-ink-2">{message}</div> : null}
      {onRetry ? (
        <button
          type="button"
          data-action="retry"
          onClick={onRetry}
          className="mt-2.5 rounded-lg border px-3 py-1 text-xs font-medium transition-colors"
          style={{ borderColor: 'var(--c-danger)', color: 'var(--c-danger)' }}
        >
          {retryLabel}
        </button>
      ) : null}
    </div>
  )
}

/** Permission-denial notice (host pickers surface these instead of errors). */
export function PermissionNotice({ message, className }: { message: ReactNode; className?: string }) {
  return (
    <div
      data-permission-notice=""
      role="note"
      className={cn('rounded-xl border px-4 py-3 text-[13px]', className)}
      style={{ borderColor: 'var(--c-warning)', background: 'var(--c-warning-bg)', color: 'var(--c-warning)' }}
    >
      {message}
    </div>
  )
}

export type FyProgressStatus = 'indeterminate' | 'determinate'

/**
 * Hairline progress bar. Determinate mode shows the percentage label inline;
 * indeterminate runs a low-contrast sweep — progress is semantic here, not
 * decoration.
 */
export function Progress({
  value,
  status = 'determinate',
  label,
  className,
}: {
  /** 0..1 */
  value?: number
  status?: FyProgressStatus
  label?: ReactNode
  className?: string
}) {
  const bounded = Math.max(0, Math.min(1, value ?? 0))
  return (
    <div data-progress="" className={cn('flex items-center gap-2.5', className)}>
      <span
        className="relative h-1 min-w-24 flex-1 overflow-hidden rounded-full"
        style={{ background: 'color-mix(in oklab, var(--c-ink) 8%, transparent)' }}
      >
        {status === 'determinate' ? (
          <span
            className="block h-full rounded-full transition-[width] duration-300"
            style={{ width: `${bounded * 100}%`, background: 'var(--c-gold)' }}
          />
        ) : (
          <span
            className="absolute inset-y-0 w-1/3 animate-[fy-indeterminate_1.4s_ease-in-out_infinite] rounded-full"
            style={{ background: 'var(--c-gold)' }}
          />
        )}
      </span>
      {label ?? (status === 'determinate' ? (
        <span className="font-mono text-[11px] tabular-nums text-ink-2">{Math.round(bounded * 100)}%</span>
      ) : null)}
    </div>
  )
}
