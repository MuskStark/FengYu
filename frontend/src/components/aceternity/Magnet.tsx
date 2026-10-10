import type { ReactNode } from 'react'
import { MagneticButton } from '@/aceternity/magnetic-button'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Magnetic hover for primary call-to-action moments (setup wizard primary
 * button, run-flow). The vendored demo chrome (dashed border + blue reveal
 * tint) is re-skinned onto brand gold by the magnetic scope rules; only the
 * spring motion itself remains upstream behavior.
 */
export function Magnet({
  children,
  strength = 0.35,
  className,
}: {
  children: ReactNode
  strength?: number
  className?: string
}) {
  return (
    <AcnScope variant="magnetic" className={cn('inline-block rounded-lg', className)}>
      <MagneticButton strength={strength}>{children}</MagneticButton>
    </AcnScope>
  )
}
