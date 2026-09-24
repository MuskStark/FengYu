import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'
import { SpotlightCard } from '@/components/aceternity/SpotlightCard'
import { FadeIn } from './FadeIn'

/** Centered loading state for a page body. */
export function PageLoading({ label }: { label?: string }) {
  const { t } = useTranslation()
  return (
    <FadeIn className="pg-state" >
      <span className="cx-spin lg" />
      <span>{label ?? t('common.loading')}</span>
    </FadeIn>
  )
}

/**
 * Empty state on a SpotlightCard — the soft mouse-following glow keeps the
 * moment friendly without shouting (calm workbench aesthetic).
 */
export function PageEmpty({ icon, title, hint }: { icon: ReactNode; title: string; hint?: string }) {
  return (
    <FadeIn>
      <SpotlightCard className="pg-state">
        {icon}
        <span>{title}</span>
        {hint && <span className="pg-state__hint">{hint}</span>}
      </SpotlightCard>
    </FadeIn>
  )
}

/** Load-failure alert with the standard retry affordance. */
export function PageError({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { t } = useTranslation()
  return (
    <FadeIn className="cx-alert cx-alert--error" role="alert">
      <AlertTriangle size={18} style={{ flexShrink: 0, marginTop: 1 }} />
      <div className="cx-alert__body">{message}</div>
      <button className="cx-btn cx-btn--sm cx-btn--outline" onClick={onRetry}>
        {t('common.retry')}
      </button>
    </FadeIn>
  )
}
