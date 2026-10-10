import { Meteors } from '@/aceternity/meteors'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Gentle gold meteor drift for dark-stage ceremonial surfaces (startup screen,
 * about). Purely decorative: aria-hidden, pointer-events none. The parent must
 * be positioned (relative/absolute) — meteors fill it via inset-0.
 */
export function MeteorShower({ number = 8, className }: { number?: number; className?: string }) {
  return (
    <AcnScope
      variant="meteors"
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-0 overflow-hidden', className)}
    >
      <Meteors number={number} />
    </AcnScope>
  )
}
