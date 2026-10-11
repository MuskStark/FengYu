import { Spotlight } from '@/aceternity/spotlight'
import { AcnScope } from './scope'

/**
 * The Aceternity hero beam: a blurred ellipse sweeping in from the top on
 * mount (one-shot animation, then static). Decorative — aria-hidden and
 * pointer-safe; the parent must be positioned. The ellipse fill follows the
 * gold glow family via the spotlight scope rule.
 */
export function SpotlightStage({ className }: { className?: string }) {
  return (
    <AcnScope
      variant="spotlight"
      aria-hidden="true"
      className={`pointer-events-none absolute inset-0 overflow-hidden ${className ?? ''}`}
    >
      <Spotlight className="-top-40 left-0" />
    </AcnScope>
  )
}
