import { TextGenerateEffect } from '@/aceternity/text-generate-effect'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Word-by-word blur-in reveal for one-shot entrance copy (hero headline,
 * 404 title). Splits on spaces, so CJK copy reveals as one block — use
 * CipherText for per-character motion in zh (app-side precedent).
 */
export function TextReveal({
  words,
  className,
  duration = 0.5,
}: {
  words: string
  className?: string
  duration?: number
}) {
  return (
    <AcnScope variant="text-reveal" className="block">
      <TextGenerateEffect
        words={words}
        duration={duration}
        className={cn('font-normal text-foreground', className)}
      />
    </AcnScope>
  )
}
