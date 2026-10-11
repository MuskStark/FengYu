import { BackgroundBeams } from '@/aceternity/background-beams'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Traveling light beams (the signature Aceternity hero effect) re-skinned to
 * the Infinia gold band by the beams scope rules. Continuously animated —
 * reserve it for ceremonial, always-on-view surfaces (chat draft home, store).
 * The parent must be positioned; beams fill it via inset-0.
 */
export function BeamsStage({ className }: { className?: string }) {
  return (
    <AcnScope
      variant="beams"
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-0 overflow-hidden', className)}
    >
      <BackgroundBeams />
    </AcnScope>
  )
}
