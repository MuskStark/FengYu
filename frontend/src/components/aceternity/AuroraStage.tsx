import type { ReactNode } from 'react'
import { AuroraBackground } from '@/aceternity/aurora-background'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Gold-band aurora backdrop for ceremonial surfaces (store hero, about). The
 * vendored component forces a `<main>` + 100vh stage — the aurora scope rules
 * turn the tag into `display: contents` and this wrapper re-heights it, so it
 * behaves like an ordinary rounded stage block inside the page flow.
 */
export function AuroraStage({
  className,
  children,
  showRadialGradient = false,
}: {
  className?: string
  children: ReactNode
  showRadialGradient?: boolean
}) {
  return (
    <AcnScope variant="aurora" className={cn('overflow-hidden rounded-[14px]', className)}>
      <AuroraBackground
        showRadialGradient={showRadialGradient}
        className="relative flex h-auto min-h-[220px] w-full flex-col items-center justify-center bg-[color:var(--color-background)] text-foreground dark:bg-[color:var(--color-background)]"
      >
        {children}
      </AuroraBackground>
    </AcnScope>
  )
}
