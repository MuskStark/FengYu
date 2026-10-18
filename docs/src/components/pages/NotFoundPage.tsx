import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { CipherText } from '@/components/aceternity/CipherText'
import { Magnet } from '@/components/aceternity/Magnet'
import { TextReveal } from '@/components/aceternity/TextReveal'
import { t } from '@/i18n'

/** The one page allowed a bit of Aceterness: a decode/blur-reveal title over the brand-gradient 404, with a magnetic way back. */
export default function NotFoundPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const locale = location.pathname.split('/')[1] === 'zh' ? 'zh' : 'en'
  const L = t(locale)

  useEffect(() => {
    document.title = '404 · Infinia Docs'
  }, [])

  return (
    <div className="doc-article notfound">
      <p className="notfound-code" aria-hidden="true">
        404
      </p>
      <h1 className="notfound-title">
        {locale === 'zh' ? <CipherText text={L.notFoundTitle} /> : <TextReveal words={L.notFoundTitle} duration={0.5} />}
      </h1>
      <p className="notfound-body">{L.notFoundBody}</p>
      <div className="notfound-actions">
        <Magnet strength={0.3}>
          <button
            type="button"
            className="home-cta home-cta--primary"
            onClick={() => navigate(locale === 'zh' ? '/zh/quickstart' : '/en/quickstart')}
          >
            {L.backHome}
          </button>
        </Magnet>
      </div>
      <p className="notfound-path">
        <code>{location.pathname}</code>
      </p>
    </div>
  )
}
