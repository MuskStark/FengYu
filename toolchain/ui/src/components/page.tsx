import type { CSSProperties, ReactNode } from 'react'
import { cn } from '../lib/utils'

/**
 * Content container for a plugin page. `fluid` removes the max-width for
 * editor/canvas-style workspaces; `fullHeight` lets such pages fill the
 * viewport (the T1 写作台 default). Mirrors the Vue kit's FyPluginPage
 * contract in Infinia form.
 */
export function Page({
  children,
  className,
  maxWidth = 980,
  fluid = false,
  fullHeight = false,
  style,
}: {
  children: ReactNode
  className?: string
  /** Max content width in px; ignored when `fluid`. */
  maxWidth?: number
  fluid?: boolean
  fullHeight?: boolean
  style?: CSSProperties
}) {
  return (
    <div
      data-plugin-page=""
      className={cn(
        'min-h-0 min-w-0 flex-1',
        fullHeight ? 'flex flex-col overflow-hidden' : 'overflow-y-auto',
        className,
      )}
      style={{ ...style, ...(fluid ? {} : { maxWidth }) }}
    >
      <div className={cn('mx-auto w-full px-6', fullHeight ? 'flex min-h-0 flex-1 flex-col' : 'py-6')}>
        {children}
      </div>
    </div>
  )
}

/** Section heading row with an optional trailing action. */
export function PageHeader({
  title,
  description,
  right,
  className,
}: {
  title: ReactNode
  description?: ReactNode
  right?: ReactNode
  className?: string
}) {
  return (
    <header className={cn('mb-4 flex items-start gap-4', className)}>
      <div className="min-w-0">
        <h2 className="text-[15px] font-semibold tracking-tight">{title}</h2>
        {description ? <p className="mt-0.5 text-[13px] text-ink-2">{description}</p> : null}
      </div>
      {right ? <div className="ml-auto flex shrink-0 items-center gap-2">{right}</div> : null}
    </header>
  )
}
