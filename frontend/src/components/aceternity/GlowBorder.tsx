import type { ReactNode } from 'react'
import { MovingBorder } from '@/aceternity/moving-border'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * A gold light comet continuously circling the host's border line — the
 * Aceternity moving-border primitive as a zero-layout overlay: the child keeps
 * its own skin and geometry, the glow simply rides 1px outside it. Always-on
 * by design (one getTotalLength + transform per frame); the glow scope rules
 * re-skin upstream's demo colors.
 */
export function GlowBorder({
  borderRadius = '16px',
  duration = 5000,
  className,
  children,
}: {
  borderRadius?: string
  duration?: number
  className?: string
  children: ReactNode
}) {
  return (
    <AcnScope variant="glow" className={cn('relative', className)}>
      {children}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -inset-px z-10"
        style={{ borderRadius }}
      >
        <MovingBorder duration={duration} rx="30%" ry="30%">
          <div className="h-14 w-14 bg-[radial-gradient(var(--color-brand)_35%,transparent_65%)] opacity-70 blur-[3px]" />
        </MovingBorder>
      </div>
    </AcnScope>
  )
}
