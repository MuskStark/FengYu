import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { LogIn, LogOut } from 'lucide-react'
import '@/styles/pages.css'
import { services } from '@/services'
import type { AccountStoreProfile, AccountView } from '@/services/account'
import { getPlatform } from '@/platform'
import { FadeIn } from '@/components/pages/FadeIn'
import { PageError, PageLoading } from '@/components/pages/StateViews'
import { BEE_LEVELS, BeeLevelBadge, beeMark } from '@/components/pages/BeeLevelBadge'
import { cn } from '@/lib/utils'

const SIGN_IN_POLL_INTERVAL_MS = 1500
const SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000

/**
 * Account center (React twin of the Vue AccountProfile's core): the local
 * account identity card plus the Infinia Level badge. Signed out, the page
 * degrades to the local-account card with a sign-in entry that drives the
 * host's browser OAuth flow; the full profile/security surface lands with its
 * own batch.
 */
export default function AccountPage() {
  const { t } = useTranslation()
  const [account, setAccount] = useState<AccountView | null>(null)
  const [profile, setProfile] = useState<AccountStoreProfile | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [signInPending, setSignInPending] = useState(false)
  const [signInError, setSignInError] = useState<string | null>(null)
  const pollAborted = useRef(false)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const me = await services.account.me()
      setAccount(me)
      // The store profile (beeLevel) only exists behind a cloud session — a 401
      // simply means "local account", never an error surface.
      setProfile(me.authenticated ? await services.account.storeProfile().catch(() => null) : null)
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    pollAborted.current = false
    void reload()
    return () => { pollAborted.current = true }
  }, [reload])

  /** Browser OAuth round-trip: open the authorization URL, poll the attempt. */
  async function signIn(): Promise<void> {
    if (busy) return
    setBusy(true)
    setSignInError(null)
    setSignInPending(true)
    try {
      const started = await services.account.startSignIn()
      await getPlatform().openExternal(started.authorizationUrl)
      const deadline = Date.now() + SIGN_IN_TIMEOUT_MS
      while (!pollAborted.current) {
        if (Date.now() > deadline) throw new Error(t('common.unexpectedError'))
        const attempt = await services.account.signInStatus(started.attemptId)
        if (attempt.status === 'COMPLETED' && attempt.user) {
          await reload()
          return
        }
        if (attempt.status === 'FAILED') throw new Error(attempt.error || t('common.unexpectedError'))
        await new Promise(resolve => window.setTimeout(resolve, SIGN_IN_POLL_INTERVAL_MS))
      }
    } catch (e) {
      if (!pollAborted.current) {
        setSignInError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
      }
    } finally {
      setSignInPending(false)
      setBusy(false)
    }
  }

  async function signOut(): Promise<void> {
    if (busy) return
    setBusy(true)
    try {
      await services.account.signOut()
      await reload()
    } catch (e) {
      setSignInError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setBusy(false)
    }
  }

  const signedIn = account?.authenticated === true
  const displayName = account?.username || account?.email?.split('@')[0] || t('account.defaultName')
  const level = beeMark(profile?.beeLevel ?? 0)
  const nextLevel = level.level < 4 ? level.level + 1 : null
  const initials = displayName.trim().charAt(0).toUpperCase() || 'U'

  function roleLabel(role: string): string {
    const key = `account.roleLabels.${role}`
    const translated = t(key)
    return translated === key ? role : translated
  }

  return (
    <div className="pg-page">
      <div className="pg-inner">
        <FadeIn>
          <header className="pg-header">
            <div className="pg-header__text">
              <h1 className="pg-title">{t('account.title')}</h1>
              <p className="cx-muted pg-subtitle">{t('account.overviewHint')}</p>
            </div>
          </header>
        </FadeIn>
        <div style={{ height: 6 }} />

        {error && <PageError message={error} onRetry={() => void reload()} />}
        {loading && !account
          ? <PageLoading />
          : account && (
            <div className="pg-account-grid">
              <FadeIn delay={0.04}>
                <div className="cx-card pg-account-card">
                  <div className="pg-account-id">
                    <span className="cx-avatar pg-account-avatar">{initials}</span>
                    <div className="cx-grow">
                      <div className="pg-account-name">{displayName}</div>
                      <span className={cn('cx-chip', signedIn && 'cx-chip--success')}>
                        {signedIn ? t('account.signedIn') : t('account.localAccount')}
                      </span>
                    </div>
                  </div>
                  {account.email && <p className="cx-muted pg-account-hint" style={{ margin: 0 }}>{account.email}</p>}
                  {account.roles.length > 0 && (
                    <div>
                      <div className="cx-label" style={{ marginBottom: 7 }}>{t('account.roles')}</div>
                      <div className="pg-card-row">
                        {account.roles.map(role => (
                          <span key={role} className="cx-chip">{roleLabel(role)}</span>
                        ))}
                      </div>
                    </div>
                  )}
                  <div style={{ marginTop: 'auto', display: 'flex', gap: 8 }}>
                    {signedIn
                      ? (
                        <button className="cx-btn cx-btn--outline" disabled={busy} onClick={() => void signOut()}>
                          <LogOut size={15} />
                          {t('account.signOut')}
                        </button>
                      )
                      : (
                        <button className="cx-btn cx-btn--primary" disabled={busy} onClick={() => void signIn()}>
                          <LogIn size={15} />
                          {t('account.signIn')}
                        </button>
                      )}
                  </div>
                  {signInPending && <p className="cx-muted pg-account-hint">{t('account.signInPending')}</p>}
                  {!signInPending && !signedIn && <p className="cx-muted pg-account-hint">{t('account.signInHint')}</p>}
                  {signInError && (
                    <div className="cx-alert cx-alert--error" role="alert">
                      <div className="cx-alert__body">{signInError}</div>
                    </div>
                  )}
                </div>
              </FadeIn>

              <FadeIn delay={0.08}>
                <div className="cx-card pg-account-card">
                  {/* Infinia Level badge — placeholder view until the full membership surface lands. */}
                  <BeeLevelBadge level={level.level} />
                  <div className="pg-level-ladder" aria-hidden="true">
                    {BEE_LEVELS.map(step => (
                      <span
                        key={step}
                        className={cn('pg-level-dot', step <= level.level && 'pg-level-dot--on')}
                        title={t(`account.beeLevel.${step}`)}
                      />
                    ))}
                    <span className="pg-level-next">
                      {nextLevel !== null
                        ? t('account.levelNext', { next: t(`account.beeLevel.${nextLevel}`) })
                        : t('account.levelTop')}
                    </span>
                  </div>
                </div>
              </FadeIn>
            </div>
          )}
      </div>
    </div>
  )
}
