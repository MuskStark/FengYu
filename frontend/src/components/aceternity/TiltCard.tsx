import type { ReactNode } from 'react'
import { CardContainer } from '@/aceternity/3d-card'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Pointer-tilt wrapper over the vendored 3d-card container: the card rotates
 * toward the cursor (perspective + rotateX/Y) while keeping its own skin and
 * layout — unlike upstream's demo CardBody (fixed h-96/w-96) this only borrows
 * the tilt mechanics. Reduced-motion disables the rotation via the tilt scope
 * rules.
 */
export function TiltCard({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <AcnScope variant="tilt" className={cn('h-full', className)}>
      <CardContainer containerClassName="!py-0 h-full" className="h-full w-full">
        {children}
      </CardContainer>
    </AcnScope>
  )
}
