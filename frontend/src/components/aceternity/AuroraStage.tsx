import type { ReactNode } from 'react'
import { AuroraBackground } from '@/aceternity/aurora-background'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Full-width gold aurora banner — the app's one ceremonial signature surface.
 * The vendored component forces a `<main>` + 100vh stage; the aurora scope
 * rules turn the tag into a plain div and this wrapper re-heights it, and the
 * wrapper flexes to the full banner width (as a flex item inside .pg-header it
 * would otherwise shrink to its content). The radial corner mask stays OFF so
 * the band fills the banner edge to edge; the 0.6 veil opacity keeps it calm.
 */
export function AuroraStage({
  className,
  children,
}: {
  className?: string
  children: ReactNode
}) {
  return (
    <AcnScope variant="aurora" className={cn('w-full flex-1 overflow-hidden rounded-[14px]', className)}>
      <AuroraBackground
        showRadialGradient={false}
        className="relative flex h-auto min-h-[210px] w-full flex-col items-center justify-center gap-1 bg-[color:var(--color-background)] px-7 py-10 text-foreground dark:bg-[color:var(--color-background)]"
      >
        {children}
      </AuroraBackground>
    </AcnScope>
  )
}
