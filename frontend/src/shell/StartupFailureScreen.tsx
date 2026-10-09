import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getPlatform, type BootStateEvent } from '@/platform'
import logoUrl from '@/assets/infinia-logo.svg'

/**
 * In-app boot-failure screen — the recoverable counterpart of the native
 * dialog + quit the shell used to show (that dialog survives as the fallback for
 * a renderer that never mounts this screen; see desktop ipc/boot.ts).
 *
 * The shell pushes `boot:state {phase:'failed'}`; BootGate swaps this in. On
 * mount it ACKs visibility (disarming the shell's fallback timer), and offers
 * retry (the shell respawns a spawned backend end-to-end, or re-polls an
 * externally-owned one), opening the log folder, copying diagnostics (never
 * the token), and quitting.
 */
export default function StartupFailureScreen({ failure }: { failure: BootStateEvent }) {
  const { t } = useTranslation()
  const platform = getPlatform()
  const [copied, setCopied] = useState(false)
  const copyTimer = useRef<number>(0)

  useEffect(() => {
    platform.ackBootFailure()
  }, [platform])

  useEffect(() => () => window.clearTimeout(copyTimer.current), [])

  const reason = failure.reason ?? 'health-deadline'
  const diagnostics = JSON.stringify(
    {
      ...failure,
      apiBase: platform.apiBase(),
      userAgent: navigator.userAgent,
      at: new Date().toISOString(),
    },
    null,
    2,
  )

  const handleRetry = () => {
    // The shell pushes 'booting' the moment retry starts (BootGate swaps back to
    // StartupScreen) and a fresh 'failed' if it fails again — this component only
    // needs to fire the request.
    void platform.retryBoot().catch(() => {
      /* failure state arrives via boot:state */
    })
  }

  const handleCopy = () => {
    void platform
      .copyText(diagnostics)
      .then(() => {
        setCopied(true)
        copyTimer.current = window.setTimeout(() => setCopied(false), 2000)
      })
      .catch(() => {
        /* clipboard refused — keep the button in its default label */
      })
  }

  const openLogs = () => {
    void platform.openLogsFolder()
  }

  return (
    <div className="fx-startup fx-bootfail" role="alert">
      <img className="fx-startup__logo fx-bootfail__logo" src={logoUrl} alt="" width={72} height={72} />
      <div className="fx-bootfail__card">
        <h1 className="fx-bootfail__title">{t('boot.failedTitle')}</h1>
        <p className="fx-bootfail__body">{t(`boot.reason.${reason}`)}</p>
        {failure.detail ? <p className="fx-bootfail__detail">{failure.detail}</p> : null}
        {typeof failure.exitCode === 'number' ? (
          <p className="fx-bootfail__detail">
            {t('boot.exitCode')}: {failure.exitCode}
          </p>
        ) : null}
        <div className="fx-bootfail__actions">
          <button type="button" className="cx-btn cx-btn--primary" onClick={handleRetry}>
            {t('boot.retry')}
          </button>
          <button type="button" className="cx-btn cx-btn--outline" onClick={openLogs}>
            {t('boot.openLogs')}
          </button>
          <button type="button" className="cx-btn cx-btn--outline" onClick={handleCopy}>
            {copied ? t('boot.copied') : t('boot.copyDiagnostics')}
          </button>
          <button type="button" className="cx-btn cx-btn--text" onClick={() => platform.quitApp()}>
            {t('boot.quit')}
          </button>
        </div>
      </div>
    </div>
  )
}
