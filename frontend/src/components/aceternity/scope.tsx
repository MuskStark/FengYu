import { MotionConfig } from 'motion/react'
import type { HTMLAttributes, ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * Shared wrapper chassis for vendored Aceternity components: the `.acn-scope`
 * class activates the token re-skin rules in styles/aceternity.css (upstream
 * ships zinc/slate/blue demo palettes), and reduced-motion is honored app-wide
 * (MotionConfig "user" — ToolsCardGrid precedent).
 */
export function AcnScope({
  variant,
  as: Tag = 'div',
  className,
  children,
  ...rest
}: {
  variant?: 'aurora' | 'meteors' | 'magnetic' | 'text-reveal' | 'lens' | 'tilt' | 'spotlight'
} & HTMLAttributes<HTMLElement> & { as?: 'div' | 'span'; children: ReactNode }) {
  return (
    <MotionConfig reducedMotion="user">
      <Tag className={cn('acn-scope', variant && `acn-scope--${variant}`, className)} {...rest}>
        {children}
      </Tag>
    </MotionConfig>
  )
}
