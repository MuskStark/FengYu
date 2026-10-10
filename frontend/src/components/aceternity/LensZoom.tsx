import type { ReactNode } from 'react'
import { Lens } from '@/aceternity/lens'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Hover loupe for image content (chat attachments, generated images): a
 * circular magnifier follows the pointer over the media. The upstream root
 * fixes rounded-lg/z-20 — the lens scope rules relax both so the container
 * inherits the host surface's radius and stacking stays predictable.
 */
export function LensZoom({
  children,
  zoomFactor = 2.2,
  lensSize = 120,
  className,
}: {
  children: ReactNode
  zoomFactor?: number
  lensSize?: number
  className?: string
}) {
  return (
    <AcnScope variant="lens" className={cn('block h-full w-full', className)}>
      <Lens zoomFactor={zoomFactor} lensSize={lensSize}>
        {children}
      </Lens>
    </AcnScope>
  )
}
