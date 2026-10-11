import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'
import { CipherText } from '@/components/aceternity/CipherText'
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
 * Bento-card skeletons for catalog grids: placeholder cards mirror the card
 * anatomy (icon disc, title, meta, description lines, action pill) so the
 * loaded content settles into place instead of jumping. Pure opacity pulse —
 * no motion to disable.
 */
export function BentoSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div className="pg-grid pg-grid--equal pg-skel-grid" role="status" aria-label="loading" aria-busy="true">
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="pg-sk-card">
          <div className="pg-sk-head">
            <span className="pg-sk-disc" />
            <span className="pg-sk-line pg-sk-line--title" />
          </div>
          <span className="pg-sk-line" />
          <span className="pg-sk-line pg-sk-line--short" />
          <span className="pg-sk-pill" />
        </div>
      ))}
    </div>
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
        <span><CipherText text={title} /></span>
        {hint && <span className="pg-state__hint">{hint}</span>}
      </SpotlightCard>
    </FadeIn>
  )
}

/** Load-failure alert with the standard retry affordance; `detail` keeps the raw
 *  backend reason (often English) under a localized headline without hiding it. */
export function PageError({ message, detail, onRetry }: {
  message: string
  detail?: string
  onRetry: () => void
}) {
  const { t } = useTranslation()
  return (
    <FadeIn className="cx-alert cx-alert--error" role="alert">
      <AlertTriangle size={18} style={{ flexShrink: 0, marginTop: 1 }} />
      <div className="cx-alert__body">
        {message}
        {detail && <div className="cx-muted" style={{ fontSize: 12 }}>{detail}</div>}
      </div>
      <button className="cx-btn cx-btn--sm cx-btn--outline" onClick={onRetry}>
        {t('common.retry')}
      </button>
    </FadeIn>
  )
}
