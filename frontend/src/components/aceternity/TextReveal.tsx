import { TextGenerateEffect } from '@/aceternity/text-generate-effect'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Word-by-word blur-in reveal for one-shot entrance copy (page subtitles,
 * setup/welcome lines). The vendored component bolds and enlarges by demo
 * default — the text-reveal scope rules and the className here calm it back
 * into the app's type scale. Splits on spaces, so CJK copy reveals as one
 * block (use CipherText for per-character motion in zh).
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
    <AcnScope as="span" variant="text-reveal" className="block">
      <TextGenerateEffect
        words={words}
        duration={duration}
        className={cn('font-normal text-foreground-subtle', className)}
      />
    </AcnScope>
  )
}
