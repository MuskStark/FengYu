import { FlipWords } from '@/aceternity/flip-words'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Cycling word flip for short rotating accents (empty-state verbs, feature
 * chips). Words come from the caller — pass i18n strings, not literals.
 */
export function FlipCycle({
  words,
  duration = 3200,
  className,
}: {
  words: string[]
  duration?: number
  className?: string
}) {
  return (
    <AcnScope as="span" className="inline">
      <FlipWords words={words} duration={duration} className={cn('px-0 text-accent', className)} />
    </AcnScope>
  )
}
