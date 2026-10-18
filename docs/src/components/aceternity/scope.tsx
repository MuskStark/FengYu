import { MotionConfig } from 'motion/react'
import type { HTMLAttributes, ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * Shared wrapper chassis for vendored Aceternity components (docs twin of the
 * app's frontend/src/components/aceternity/scope.tsx): the `.acn-scope` class
 * activates the token re-skin rules in styles/foundation/aceternity.css
 * (upstream ships slate/zinc/cyan demo palettes), and reduced-motion is
 * honored site-wide (MotionConfig "user").
 */
export function AcnScope({
  variant,
  as: Tag = 'div',
  className,
  children,
  ...rest
}: {
  variant?: 'lamp' | 'text-reveal' | 'cipher' | 'magnetic' | 'cards'
} & HTMLAttributes<HTMLElement> & { as?: 'div' | 'span'; children: ReactNode }) {
  return (
    <MotionConfig reducedMotion="user">
      <Tag className={cn('acn-scope', variant && `acn-scope--${variant}`, className)} {...rest}>
        {children}
      </Tag>
    </MotionConfig>
  )
}
