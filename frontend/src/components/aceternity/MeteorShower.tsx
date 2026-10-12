import { useMemo } from 'react'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/* Orientation comes solely from the meteor-effect keyframes (215° diagonal, in
   aceternity.css) — no static rotate utility, which is what let a delayed
   meteor sit on screen in one pose and snap to another when the flight began.
   opacity-0 is the belt-and-braces guard: any frame not governed by the
   keyframes shows nothing at all. */
const METEOR_SPAN_CLS = cn(
  'animate-meteor-effect absolute h-0.5 w-0.5 rounded-[9999px] bg-slate-500 opacity-0',
  'shadow-[0_0_0_1px_#ffffff10]',
  'before:absolute before:top-1/2 before:h-[1px] before:w-[50px] before:-translate-y-[50%] before:transform before:bg-gradient-to-r before:from-[#64748b] before:to-transparent before:content-[""]',
)

/**
 * Gentle gold meteor drift for dark-stage ceremonial surfaces (startup screen,
 * about). Purely decorative: aria-hidden, pointer-events none. The parent must
 * be positioned (relative/absolute) — meteors fill it via inset-0.
 *
 * Spans are generated here rather than delegating to the vendored `<Meteors/>`:
 * upstream anchors an 800px spread to the container's left edge and starts
 * every streak above the top (fine for its demo card, but on a full-screen
 * surface the shower piles into the top-left corner). Start points here spread
 * across the whole surface; the keyframes' backwards fill + 0% opacity:0 keep
 * a delayed meteor invisible until it actually departs, so starts can sit
 * anywhere on screen without a motionless pre-flight pose showing.
 */
export function MeteorShower({ number = 8, className }: { number?: number; className?: string }) {
  const meteors = useMemo(
    () =>
      Array.from({ length: number }, (_, i) => ({
        // Horizontal coverage comes from even slots with jitter (no clustering);
        // vertical starts are pure random over the top 65% so the two axes stay
        // uncorrelated — slot-pairing both showed up as a diagonal band. A
        // streak still travels ~40% of the viewport while fading, so the lower
        // third stays populated too.
        left: ((i + 0.1 + Math.random() * 0.8) / number) * 100,
        top: Math.random() * 65,
        delay: Math.random() * 5,
        duration: 5 + Math.random() * 4,
      })),
    [number],
  )
  return (
    <AcnScope
      variant="meteors"
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-0 overflow-hidden', className)}
    >
      {meteors.map((m, i) => (
        <span
          key={i}
          className={METEOR_SPAN_CLS}
          style={{
            top: `${m.top}%`,
            left: `${m.left}%`,
            animationDelay: `${m.delay}s`,
            animationDuration: `${m.duration}s`,
          }}
        />
      ))}
    </AcnScope>
  )
}
