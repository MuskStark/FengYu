import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Compass } from 'lucide-react'
import { Magnet } from '@/components/aceternity/Magnet'
import { TextReveal } from '@/components/aceternity/TextReveal'
import '@/styles/features/pages.css'

/** 404 — unknown routes land here (deep links, retired paths, typos). */
export default function NotFoundPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <div className="cx-page">
      <div className="cx-card nf-card">
        <div className="flex items-center gap-2">
          <Compass size={20} />
          <h1 className="cx-page-title cx-page-title--flush">{t('notFound.title')}</h1>
        </div>
        <TextReveal words={t('notFound.hint')} className="cx-muted" />
        <Magnet strength={0.25}>
          <button className="cx-btn cx-btn--primary" onClick={() => navigate('/')}>
            {t('notFound.backHome')}
          </button>
        </Magnet>
      </div>
    </div>
  )
}
