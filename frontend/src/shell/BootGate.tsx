import { useEffect } from 'react'
import { services } from '@/services'
import logoUrl from '@/assets/infinia-logo.svg'

/**
 * First-boot readiness gate (backend still starting): a calm skeleton with a health poll;
 * hands over to the app the moment /api/health answers. React port of the Vue BootGate.
 */
export default function BootGate({ onReady }: { onReady: () => void }) {
  useEffect(() => {
    const timer = window.setInterval(async () => {
      try {
        await services.system.health()
        window.clearInterval(timer)
        onReady()
      } catch {
        /* keep waiting */
      }
    }, 900)
    return () => window.clearInterval(timer)
  }, [onReady])

  return (
    <div className="fx-bootgate">
      <img src={logoUrl} alt="" width={44} height={44} />
      <span className="cx-spin" />
    </div>
  )
}
