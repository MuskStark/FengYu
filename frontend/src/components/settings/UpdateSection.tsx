import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ExternalLink, RefreshCw } from 'lucide-react'
import type { AppSettings, UpdateCheckResult } from '@/services/types'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import { useToastStore } from '@/stores/toasts'
import SettingRow from './SettingRow'

/**
 * Section 7 — updates: version display + check (desktop routes through the shell's
 * electron-updater bridge, portable/web through GET /api/updates/check; portable can
 * self-swap the JAR via POST /api/updates/apply), plus the two update-channel
 * AppSettings fields (updateApiBase, storeAllowPrivateNetwork).
 */
export default function UpdateSection({
  appSettings,
  update,
}: {
  appSettings: AppSettings | null
  update: (partial: Partial<AppSettings>) => Promise<void>
}) {
  const { t } = useTranslation()
  const pushToast = useToastStore(state => state.push)
  const [checking, setChecking] = useState(false)
  const [result, setResult] = useState<UpdateCheckResult | null>(null)
  const [checkError, setCheckError] = useState<string | null>(null)
  const [applying, setApplying] = useState(false)
  const [proxyUrl, setProxyUrl] = useState(appSettings?.updateApiBase ?? '')
  const [proxyError, setProxyError] = useState<string | null>(null)

  // The settings snapshot loads async — adopt the stored channel URL once it arrives.
  useEffect(() => {
    setProxyUrl(appSettings?.updateApiBase ?? '')
  }, [appSettings])

  async function check(): Promise<void> {
    if (checking) return
    setChecking(true)
    setCheckError(null)
    try {
      const platform = getPlatform()
      if (platform.capabilities.desktopUpdater) {
        const r = await platform.checkForUpdates()
        setResult({
          currentVersion: '', latestVersion: r.version ?? '', updateAvailable: r.updateAvailable,
          releaseUrl: r.releaseUrl ?? '', releaseName: r.version ?? '', publishedAt: '',
          prerelease: false, releaseNotes: '', portableMode: false, downloadAssetUrl: null,
        })
      } else {
        setResult(await services.appUpdate.check(true))
      }
    } catch (error) {
      setCheckError(error instanceof Error ? error.message : String(error))
    } finally {
      setChecking(false)
    }
  }

  async function upgradeNow(): Promise<void> {
    if (applying) return
    setApplying(true)
    setCheckError(null)
    try {
      const platform = getPlatform()
      if (platform.capabilities.desktopUpdater) {
        const r = await platform.downloadAndInstall()
        if (r.action === 'manual') {
          pushToast({ level: 'warning', title: t('update.macManual') })
        } else {
          pushToast({ level: 'info', title: t('update.restarting') })
        }
        return
      }
      if (result?.portableMode && result.downloadAssetUrl) {
        await services.appUpdate.applyPortable()
        pushToast({ level: 'info', title: t('update.portableRestart') })
        return
      }
      await platform.openExternal(result?.releaseUrl || 'https://github.com/MuskStark/FengYu/releases')
    } catch (error) {
      setCheckError(error instanceof Error ? error.message : String(error))
    } finally {
      setApplying(false)
    }
  }

  async function saveProxy(): Promise<void> {
    setProxyError(null)
    try {
      await update({ updateApiBase: proxyUrl.trim() })
      pushToast({ level: 'success', title: t('settings.updateProxyUrlSaved') })
      // Keep the desktop shell's updater in sync with the freshly saved channel URL.
      const platform = getPlatform()
      if (platform.capabilities.desktopUpdater) {
        await platform.setUpdateApiBase(proxyUrl.trim())
      }
    } catch (error) {
      setProxyError(error instanceof Error ? error.message : String(error))
    }
  }

  return (
    <section>
      <h2 className="set-h">{t('settings.updateChannelSection')}</h2>

      <div className="cx-card">
        <div className="cx-field" style={{ marginBottom: 14 }}>
          <label className="cx-label" htmlFor="cx-update-proxy">{t('settings.updateProxyUrl')}</label>
          <input
            id="cx-update-proxy"
            className="cx-input"
            placeholder={t('settings.updateProxyUrlPlaceholder')}
            value={proxyUrl}
            onChange={event => setProxyUrl(event.target.value)}
          />
          <div className="set-hint" style={{ margin: '4px 0 0' }}>{t('settings.updateProxyUrlHint')}</div>
        </div>

        <SettingRow label={t('settings.storeAllowPrivateTitle')}>
          <div className="cx-segment">
            <button
              type="button"
              className={!appSettings?.storeAllowPrivateNetwork ? 'active' : ''}
              onClick={() => void update({ storeAllowPrivateNetwork: false }).catch(() => {})}
            >
              {t('common.off')}
            </button>
            <button
              type="button"
              className={appSettings?.storeAllowPrivateNetwork ? 'active' : ''}
              onClick={() => void update({ storeAllowPrivateNetwork: true }).catch(() => {})}
            >
              {t('common.on')}
            </button>
          </div>
        </SettingRow>
        <div className="set-hint">{t('settings.storeAllowPrivateHint')}</div>

        <div className="set-save-row">
          <button type="button" className="cx-btn cx-btn--primary" onClick={() => void saveProxy()}>
            {t('settings.save')}
          </button>
          {proxyError ? <span className="cx-chip cx-chip--error">{proxyError}</span> : null}
        </div>
      </div>

      <div className="cx-card" style={{ marginTop: 16 }}>
        <div className="set-save-row" style={{ marginTop: 0 }}>
          <button type="button" className="cx-btn" disabled={checking} onClick={() => void check()}>
            {checking ? <span className="cx-spin" /> : <RefreshCw size={15} />}
            {checking ? t('update.checking') : result ? t('update.recheck') : t('update.check')}
          </button>
          {result?.updateAvailable && (
            <button type="button" className="cx-btn cx-btn--primary" disabled={applying} onClick={() => void upgradeNow()}>
              {applying ? <span className="cx-spin" /> : null}
              {applying ? t('update.downloading') : t('update.upgradeNow')}
            </button>
          )}
        </div>

        {result && (
          <div style={{ marginTop: 14 }}>
            {result.updateAvailable ? (
              <div className="cx-alert cx-alert--success" style={{ marginBottom: 12 }}>
                <span className="cx-alert__body">
                  {t('update.available', { version: result.latestVersion })}
                </span>
              </div>
            ) : (
              <div className="cx-chip cx-chip--success" style={{ marginBottom: 12 }}>{t('update.latest')}</div>
            )}
            <div className="upd-version-grid">
              {result.currentVersion && (
                <div className="upd-version-cell">
                  <small>{t('about.version')}</small>
                  <strong>{result.currentVersion}</strong>
                </div>
              )}
              <div className="upd-version-cell">
                <small>{t('update.newVersion')}</small>
                <strong>{result.latestVersion || '—'}</strong>
              </div>
            </div>
            {result.releaseUrl && (
              <div className="set-save-row">
                <button
                  type="button"
                  className="cx-btn cx-btn--text cx-btn--sm"
                  onClick={() => void getPlatform().openExternal(result.releaseUrl)}
                >
                  <ExternalLink size={14} />
                  {t('update.openPage')}
                </button>
              </div>
            )}
          </div>
        )}

        {checkError && (
          <div className="cx-alert cx-alert--error" style={{ marginTop: 12 }}>
            <span className="cx-alert__body">
              {t('update.error')}
              <div style={{ fontSize: 12 }}>{checkError}</div>
            </span>
          </div>
        )}
      </div>
    </section>
  )
}
