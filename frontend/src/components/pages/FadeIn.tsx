import { motion } from 'motion/react'
import type { ReactNode } from 'react'

/**
 * Calm entrance wrapper (Aceternity restraint): 0.2s fade + a slight upward
 * translate. Used for page sections and cards entering the workspace — never
 * louder than the shell itself.
 */
export function FadeIn({ children, delay = 0, className, role }: {
  children: ReactNode
  delay?: number
  className?: string
  role?: string
}) {
  return (
    <motion.div
      className={className}
      role={role}
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2, delay, ease: 'easeOut' }}
    >
      {children}
    </motion.div>
  )
}
