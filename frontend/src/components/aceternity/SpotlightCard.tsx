import { useRef, type ReactNode } from 'react'
import { motion, useMotionTemplate, useMotionValue } from 'motion/react'
import { cn } from '@/lib/utils'

/**
 * Aceternity-style spotlight card (pattern: ui.aceternity.com/components/spotlight-card —
 * copy-paste culture, reimplemented on motion + Tailwind over our token palette). A radial
 * highlight follows the pointer across the card surface; used for store/empty-state accents.
 */
export function SpotlightCard({
  children, className, spotlightColor = 'rgba(143, 214, 189, 0.18)',
}: {
  children: ReactNode
  className?: string
  spotlightColor?: string
}) {
  const mouseX = useMotionValue(0)
  const mouseY = useMotionValue(0)
  const ref = useRef<HTMLDivElement | null>(null)

  const background = useMotionTemplate`radial-gradient(280px circle at ${mouseX}px ${mouseY}px, ${spotlightColor}, transparent 72%)`

  return (
    <div
      ref={ref}
      onMouseMove={event => {
        const rect = ref.current?.getBoundingClientRect()
        if (!rect) return
        mouseX.set(event.clientX - rect.left)
        mouseY.set(event.clientY - rect.top)
      }}
      className={cn('relative overflow-hidden rounded-[14px] border border-[color:var(--cx-border)] bg-[color:rgb(var(--v-theme-surface-container))]', className)}
    >
      <motion.div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0"
        style={{ background }}
      />
      <div className="relative">{children}</div>
    </div>
  )
}
