import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft } from 'lucide-react'
import { usePluginHostFrame } from '@/lib/usePluginHostFrame'
import { usePluginsStore } from '@/stores/plugins'
import '@/styles/features/plugin-host.css'

/**
 * Plugin console page — the Tools-page iframe host. The wire contract (postMessage bridge,
 * sandbox/origin policy, uiTicket bootstrap, environment pushes) lives in the shared
 * {@link usePluginHostFrame} hook so chat app cards (PluginAppCard) and this page run the
 * SAME host implementation.
 */
export default function PluginPage({ id }: { id: string }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const plugins = usePluginsStore()
  const descriptor = plugins.plugins.find(plugin => plugin.id === id)

  const { error, loading, frameUrl, frameKey, frameSandbox, frameAllow, frameRef, retry, handleFrameLoad }
    = usePluginHostFrame(id)

  return (
    <div className="plugin-host">
      <div className="cx-topbar">
        <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => navigate('/tools')}>
          <ArrowLeft size={16} />{t('common.back')}
        </button>
        <span className="font-semibold">{descriptor?.name ?? id}</span>
      </div>
      {error ? (
        <div className="cx-alert cx-alert--error m-4">
          <div>
            <div className="font-[650] mb-1">{t('plugin.failedTitle')}</div>
            <div>{error}</div>
          </div>
          <button className="cx-btn cx-btn--outline cx-btn--sm mt-3" onClick={() => void retry()}>
            {t('plugin.retry')}
          </button>
        </div>
      ) : (
        <div className="frame-wrap">
          <iframe
            key={frameKey}
            ref={frameRef}
            className="plugin-frame"
            src={frameUrl}
            sandbox={frameSandbox}
            allow={frameAllow}
            referrerPolicy="no-referrer"
            onLoad={handleFrameLoad}
          />
          {loading && <div className="frame-loading"><span className="cx-spin lg" /></div>}
        </div>
      )}
    </div>
  )
}
