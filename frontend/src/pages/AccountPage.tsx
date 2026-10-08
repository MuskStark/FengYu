import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { i18n } from '@/i18n'
import { useNavigate } from 'react-router-dom'
import {
  ArrowUpRight, ChevronLeft, ChevronRight, KeyRound, Library, LogIn, LogOut, Package,
  ShieldCheck, Store as StoreIcon, UserCog, Users,
} from 'lucide-react'
import '@/styles/pages.css'
import { services } from '@/services'
import type {
  AccountDevice, AccountLibrary, AccountOrganization, AccountSession, AccountStoreProfile, AccountView,
} from '@/services/account'
import { getPlatform } from '@/platform'
import { useToastStore } from '@/stores/toasts'
import { FadeIn } from '@/components/pages/FadeIn'
import { PageError, PageLoading } from '@/components/pages/StateViews'
import { BEE_LEVELS, beeMark } from '@/components/pages/BeeLevelBadge'
import { cn } from '@/lib/utils'

const SIGN_IN_POLL_INTERVAL_MS = 1500
const SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000
/** Rows shown per security page (Vue parity) — a long device list stays scannable. */
const SECURITY_PAGE_SIZE = 3

/**
 * 用户中心 — the desktop mirror of the Infinia Store account page. One passport
 * (identity + membership + live counts) over paired management cards: profile
 * editing, password, sessions/devices with per-row revoke, library and
 * organization summaries, and role-aware quick access into the store web.
 * Signed out, it degrades to the local-account card that starts the browser
 * OAuth flow. Store data always flows live through the loopback proxy; only the
 * fast identity (`/api/account/me`) is DB-backed, so a store outage never
 * blocks the shell — a dead cloud session falls back to the local view.
 */
export default function AccountPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [account, setAccount] = useState<AccountView | null>(null)
  const [profile, setProfile] = useState<AccountStoreProfile | null>(null)
  const [library, setLibrary] = useState<AccountLibrary | null>(null)
  const [organizations, setOrganizations] = useState<AccountOrganization[]>([])
  const [sessions, setSessions] = useState<AccountSession[]>([])
  const [devices, setDevices] = useState<AccountDevice[]>([])
  const [storeWebBase, setStoreWebBase] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [busy, setBusy] = useState(false)
  const [signInPending, setSignInPending] = useState(false)
  const [signInError, setSignInError] = useState<string | null>(null)
  const pollAborted = useRef(false)

  const [displayNameDraft, setDisplayNameDraft] = useState('')
  const [savingProfile, setSavingProfile] = useState(false)
  const [profileMessage, setProfileMessage] = useState<string | null>(null)
  const [profileError, setProfileError] = useState<string | null>(null)

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [savingPassword, setSavingPassword] = useState(false)
  const [passwordMessage, setPasswordMessage] = useState<string | null>(null)
  const [passwordError, setPasswordError] = useState<string | null>(null)

  const [securityTab, setSecurityTab] = useState<'sessions' | 'devices'>('sessions')
  const [securityPage, setSecurityPage] = useState(0)
  const [revoking, setRevoking] = useState(false)
  const [securityError, setSecurityError] = useState<string | null>(null)

  const resetCenter = useCallback(() => {
    setProfile(null)
    setLibrary(null)
    setOrganizations([])
    setSessions([])
    setDevices([])
    setStoreWebBase(null)
    setLoadError(null)
    setSecurityTab('sessions')
    setSecurityPage(0)
    setDisplayNameDraft('')
    setCurrentPassword('')
    setNewPassword('')
    setProfileMessage(null)
    setProfileError(null)
    setPasswordMessage(null)
    setPasswordError(null)
    setSecurityError(null)
  }, [])

  const reload = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const me = await services.account.me()
      setAccount(me)
      if (!me.authenticated) {
        resetCenter()
        return
      }
      try {
        const [storeProfile, lib, activeSessions, activeDevices, status] = await Promise.all([
          services.account.storeProfile(),
          services.account.library(),
          services.account.sessions(),
          services.account.devices(),
          services.infiniaStore.status().catch(() => null),
        ])
        setProfile(storeProfile)
        setDisplayNameDraft(storeProfile.displayName ?? '')
        setLibrary(lib)
        setSessions(activeSessions)
        setDevices(activeDevices)
        setStoreWebBase((status?.apiBase ?? '').replace(/\/+$/, '') || null)
        // Memberships are optional context, never fatal.
        setOrganizations(await services.account.organizations().catch(() => []))
      } catch (e) {
        if (statusOf(e) === 401) {
          // A dead cloud session (expired public-client token, rejected refresh) makes
          // the host drop the binding — re-read /api/account/me so the page falls back
          // to the local-account view instead of a dead-end error card.
          const again = await services.account.me().catch(() => null)
          setAccount(again)
          if (!again?.authenticated) {
            resetCenter()
            return
          }
        }
        throw e
      }
    } catch (e) {
      setLoadError(messageOf(e, t))
    } finally {
      setLoading(false)
    }
  }, [t, resetCenter])

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
        setSignInError(messageOf(e, t))
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
      setSignInError(messageOf(e, t))
    } finally {
      setBusy(false)
    }
  }

  const signedIn = account?.authenticated === true
  const canSaveProfile = useMemo(() => {
    const name = displayNameDraft.trim()
    return name.length > 0 && name.length <= 64 && name !== (profile?.displayName ?? '')
  }, [displayNameDraft, profile])
  const canChangePassword = currentPassword.length > 0 && newPassword.length >= 8

  async function saveProfile(): Promise<void> {
    if (!canSaveProfile || savingProfile) return
    setSavingProfile(true)
    setProfileMessage(null)
    setProfileError(null)
    try {
      const updated = await services.account.updateProfile(displayNameDraft.trim())
      setProfile(updated)
      setDisplayNameDraft(updated.displayName ?? '')
      // Keep the fast identity view in step with the store-side rename.
      setAccount(prev => prev ? { ...prev, username: updated.displayName || prev.username } : prev)
      setProfileMessage(t('account.profileSaved'))
    } catch (e) {
      setProfileError(messageOf(e, t))
    } finally {
      setSavingProfile(false)
    }
  }

  async function changePassword(): Promise<void> {
    if (!canChangePassword || savingPassword) return
    setSavingPassword(true)
    setPasswordMessage(null)
    setPasswordError(null)
    try {
      const result = await services.account.changePassword(currentPassword, newPassword)
      setPasswordMessage(result.message || t('account.passwordChanged'))
      setCurrentPassword('')
      setNewPassword('')
    } catch (e) {
      setPasswordError(messageOf(e, t))
    } finally {
      setSavingPassword(false)
    }
  }

  async function revokeSession(sessionId: string): Promise<void> {
    if (revoking) return
    setRevoking(true)
    setSecurityError(null)
    try {
      await services.account.revokeSession(sessionId)
      setSessions(prev => prev.filter(s => s.sessionId !== sessionId))
    } catch (e) {
      setSecurityError(messageOf(e, t))
    } finally {
      setRevoking(false)
    }
  }

  async function revokeDevice(deviceId: string): Promise<void> {
    if (revoking) return
    setRevoking(true)
    setSecurityError(null)
    try {
      await services.account.revokeDevice(deviceId)
      setDevices(prev => prev.map(d => d.deviceId === deviceId ? { ...d, revoked: true } : d))
    } catch (e) {
      setSecurityError(messageOf(e, t))
    } finally {
      setRevoking(false)
    }
  }

  function openStoreWeb(path: string): void {
    if (!storeWebBase) return
    void getPlatform().openExternal(storeWebBase + path)
      // A toast, not securityError: this card has nothing to do with the login/device
      // security tab that field renders in.
      .catch(() => useToastStore.getState().push({
        level: 'error', title: t('common.unexpectedError'),
      }))
  }

  const sortedSessions = useMemo(
    () => [...sessions].sort((a, b) => (Date.parse(b.createdAt ?? '') || 0)
      - (Date.parse(a.createdAt ?? '') || 0)),
    [sessions])
  const securityRows = securityTab === 'sessions' ? sortedSessions.length : devices.length
  const pageCount = Math.max(1, Math.ceil(securityRows / SECURITY_PAGE_SIZE))
  const currentPage = Math.min(securityPage, pageCount - 1)
  const visibleSessions = sortedSessions.slice(
    currentPage * SECURITY_PAGE_SIZE, currentPage * SECURITY_PAGE_SIZE + SECURITY_PAGE_SIZE)
  const visibleDevices = devices.slice(
    currentPage * SECURITY_PAGE_SIZE, currentPage * SECURITY_PAGE_SIZE + SECURITY_PAGE_SIZE)

  const displayName = account?.username || account?.email?.split('@')[0] || t('account.defaultName')
  const passportName = profile?.displayName || profile?.email || displayName
  const initials = passportName.trim().charAt(0).toUpperCase() || 'U'
  const level = beeMark(profile?.beeLevel ?? 0)
  const nextLevel = level.level < 4 ? level.level + 1 : null
  const roles = profile?.roles ?? account?.roles ?? []

  const quickLinks = useMemo(() => {
    const links: Array<{
      key: string; desc: string; icon: typeof StoreIcon
      to?: string; external?: string
    }> = [
      { key: 'account.storePage', desc: 'account.storePageDesc', icon: StoreIcon, to: '/store' },
      { key: 'account.myLibrary', desc: 'account.myLibraryDesc', icon: Library, external: '/store/library' },
      { key: 'account.myOrganizations', desc: 'account.myOrganizationsDesc', icon: Users, external: '/store/organizations' },
      { key: 'account.manageOnline', desc: 'account.manageOnlineDesc', icon: UserCog, external: '/store/account' },
    ]
    if (roles.some(r => ['PUBLISHER', 'ORG_ADMIN', 'REVIEWER'].includes(r))) {
      links.push({
        key: 'account.publisherCenter', desc: 'account.publisherCenterDesc',
        icon: Package, external: '/publisher',
      })
    }
    if (roles.includes('PLATFORM_ADMIN')) {
      links.push({
        key: 'account.adminConsole', desc: 'account.adminConsoleDesc',
        icon: ShieldCheck, external: '/admin',
      })
    }
    return links
  }, [roles])

  function roleLabel(role: string): string {
    const key = `account.roleLabels.${role}`
    const translated = t(key)
    return translated === key ? role : translated
  }

  return (
    <div className="pg-page">
      <div className="pg-inner pg-inner--wide">
        <FadeIn>
          <header className="pg-header">
            <div className="pg-header__text">
              <h1 className="pg-title">{t('account.title')}</h1>
              <p className="cx-muted pg-subtitle">{t('account.overviewHint')}</p>
            </div>
            {signedIn && (
              <button className="cx-btn cx-btn--outline pg-acc-signout" disabled={busy} onClick={() => void signOut()}>
                <LogOut size={15} />
                {t('account.signOut')}
              </button>
            )}
          </header>
        </FadeIn>
        <div style={{ height: 6 }} />

        {loadError && !signedIn && account === null && <PageError message={loadError} onRetry={() => void reload()} />}
        {loading && !account
          ? <PageLoading />
          : account && !signedIn
            ? (
              <FadeIn delay={0.04}>
                <div className="cx-card pg-account-card pg-acc-signin">
                  <div className="pg-account-id">
                    <span className="cx-avatar pg-account-avatar">{initials}</span>
                    <div className="cx-grow">
                      <div className="pg-account-name">{displayName}</div>
                      <span className="cx-chip">{t('account.localAccount')}</span>
                    </div>
                  </div>
                  <p className="cx-muted pg-account-hint" style={{ margin: 0 }}>{t('account.signInHint')}</p>
                  <div>
                    <button className="cx-btn cx-btn--primary" disabled={busy} onClick={() => void signIn()}>
                      {signInPending ? <span className="cx-spin" /> : <LogIn size={15} />}
                      {t('account.signIn')}
                    </button>
                  </div>
                  {signInPending && <p className="cx-muted pg-account-hint">{t('account.signInPending')}</p>}
                  {signInError && (
                    <div className="cx-alert cx-alert--error" role="alert">
                      <div className="cx-alert__body">{signInError}</div>
                    </div>
                  )}
                </div>
              </FadeIn>
            )
            : account && signedIn && loadError
              ? (
                /* Escape hatch: a signed-in user whose store profile cannot load (store down,
                   session dead) must always be able to retry, re-sign-in, or sign out. */
                <FadeIn delay={0.04}>
                  <div className="cx-alert cx-alert--error" role="alert">
                    <div className="cx-alert__body">{loadError}</div>
                    <div className="pg-acc-escape">
                      <button className="cx-btn cx-btn--outline cx-btn--sm" onClick={() => void reload()}>
                        {t('common.retry')}
                      </button>
                      <button className="cx-btn cx-btn--outline cx-btn--sm" disabled={busy} onClick={() => void signIn()}>
                        {t('account.signIn')}
                      </button>
                      <button className="cx-btn cx-btn--outline cx-btn--sm pg-acc-signout" disabled={busy} onClick={() => void signOut()}>
                        {t('account.signOut')}
                      </button>
                    </div>
                  </div>
                </FadeIn>
              )
              : account && signedIn && profile && (
                <>
                  {/* Hero row mirrors the store web's account page: one wide split card
                      (identity | membership, hairline divider) + the QUICK ACCESS rail. */}
                  <div className="pg-acc-hero">
                    <FadeIn delay={0.04}>
                      <section className="cx-card pg-acc-passport">
                        <div className="pg-acc-identity">
                          <div className="pg-acc-id-row">
                            <span className="pg-acc-avatar" aria-hidden="true">{initials}</span>
                            <div className="cx-grow">
                              <h2 className="pg-acc-name">{profile.displayName || profile.userId}</h2>
                              <div className="cx-muted pg-acc-email">{profile.email}</div>
                              <div className="pg-acc-chips">
                                {(roles.length ? roles : ['USER']).map(role => (
                                  <span key={role} className="cx-chip">{roleLabel(role)}</span>
                                ))}
                              </div>
                            </div>
                          </div>
                          <dl className="pg-acc-stats">
                            <div className="pg-acc-stat">
                              <dd>{library?.entitlements?.length ?? 0}</dd>
                              <dt>{t('account.entitlementsCount')}</dt>
                            </div>
                            <div className="pg-acc-stat">
                              <dd>{devices.length}</dd>
                              <dt>{t('account.devices')}</dt>
                            </div>
                            <div className="pg-acc-stat">
                              <dd>{sessions.length}</dd>
                              <dt>{t('account.sessions')}</dt>
                            </div>
                          </dl>
                        </div>
                        <div className="pg-acc-membership">
                          <svg className="pg-acc-hexmark" viewBox="0 0 100 100" fill="none"
                              aria-hidden="true">
                            <polygon points="50 4, 92 28, 92 72, 50 96, 8 72, 8 28" />
                          </svg>
                          <p className="pg-acc-eyebrow">INFINIA · MEMBERSHIP</p>
                          <div className="pg-acc-level">
                            <span className="pg-acc-level-name">{t(`account.beeLevel.${level.level}`)}</span>
                            <span className="pg-acc-level-num">Lv{level.level}</span>
                          </div>
                          <p className="pg-acc-level-note">
                            {nextLevel !== null
                              ? t('account.levelNext', { next: t(`account.beeLevel.${nextLevel}`) })
                              : t('account.levelTop')}
                          </p>
                          <div className="pg-acc-pilltrack" aria-hidden="true">
                            {BEE_LEVELS.map(step => (
                              <span
                                key={step}
                                className={cn('pg-acc-pill', step <= level.level && 'pg-acc-pill--on')}
                              />
                            ))}
                          </div>
                        </div>
                      </section>
                    </FadeIn>
                    <FadeIn delay={0.08}>
                      <nav className="cx-card pg-acc-access" aria-label={t('account.quickLinks')}>
                        <h2 className="pg-acc-eyebrow pg-acc-eyebrow--muted">{t('account.quickLinks')}</h2>
                        {quickLinks.map(link => (
                          <button
                            key={link.key}
                            className="pg-acc-access-link"
                            disabled={!link.to && !storeWebBase}
                            onClick={() => link.to ? navigate(link.to) : openStoreWeb(link.external ?? '')}
                          >
                            <span className="pg-acc-hextile" aria-hidden="true"><link.icon size={15} /></span>
                            <span className="pg-acc-access-text">
                              <span className="pg-acc-access-title">{t(link.key)}</span>
                              <span className="pg-acc-access-desc">{t(link.desc)}</span>
                            </span>
                            <ArrowUpRight size={15} aria-hidden="true" />
                          </button>
                        ))}
                      </nav>
                    </FadeIn>
                  </div>

                  <div className="pg-acc-grid">
                    <FadeIn delay={0.1}>
                      <section className="cx-card pg-account-card">
                        <h2 className="pg-acc-card-title">{t('account.editProfile')}</h2>
                        <p className="cx-muted pg-acc-desc">{t('account.profileHint')}</p>
                        <form className="pg-acc-form" onSubmit={event => { event.preventDefault(); void saveProfile() }}>
                          <label className="cx-field cx-grow">
                            <span className="cx-label">{t('account.displayName')}</span>
                            <input
                              className="cx-input"
                              maxLength={64}
                              value={displayNameDraft}
                              disabled={savingProfile}
                              onChange={event => setDisplayNameDraft(event.target.value)}
                            />
                          </label>
                          <button type="submit" className="cx-btn cx-btn--primary" disabled={!canSaveProfile || savingProfile}>
                            {t('common.confirm')}
                          </button>
                        </form>
                        {profileMessage && <p className="pg-acc-msg pg-acc-msg--ok" role="status">{profileMessage}</p>}
                        {profileError && <p className="pg-acc-msg pg-acc-msg--err" role="alert">{profileError}</p>}
                        <hr className="pg-acc-divider" />
                        <h3 className="pg-acc-card-title">
                          <KeyRound size={15} aria-hidden="true" /> {t('account.changePassword')}
                        </h3>
                        <form className="pg-acc-form pg-acc-form--stack" onSubmit={event => { event.preventDefault(); void changePassword() }}>
                          <label className="cx-field">
                            <span className="cx-label">{t('account.currentPassword')}</span>
                            <input
                              type="password"
                              className="cx-input"
                              autoComplete="current-password"
                              value={currentPassword}
                              disabled={savingPassword}
                              onChange={event => setCurrentPassword(event.target.value)}
                            />
                          </label>
                          <label className="cx-field">
                            <span className="cx-label">{t('account.newPassword')}</span>
                            <input
                              type="password"
                              className="cx-input"
                              minLength={8}
                              maxLength={128}
                              autoComplete="new-password"
                              value={newPassword}
                              disabled={savingPassword}
                              onChange={event => setNewPassword(event.target.value)}
                            />
                          </label>
                          <button type="submit" className="cx-btn cx-btn--primary" disabled={!canChangePassword || savingPassword}>
                            {t('account.changePassword')}
                          </button>
                        </form>
                        {passwordMessage && <p className="pg-acc-msg pg-acc-msg--ok" role="status">{passwordMessage}</p>}
                        {passwordError && <p className="pg-acc-msg pg-acc-msg--err" role="alert">{passwordError}</p>}
                      </section>
                    </FadeIn>

                    <FadeIn delay={0.14}>
                      <section className="cx-card pg-account-card">
                        <h2 className="pg-acc-card-title">
                          <ShieldCheck size={15} aria-hidden="true" /> {t('account.signinDevices')}
                        </h2>
                        <p className="cx-muted pg-acc-desc">{t('account.securityHint')}</p>
                        <div className="pg-acc-tabs" role="group" aria-label={t('account.signinDevices')}>
                          {(['sessions', 'devices'] as const).map(tab => (
                            <button
                              key={tab}
                              aria-pressed={securityTab === tab}
                              onClick={() => { setSecurityTab(tab); setSecurityPage(0) }}
                            >
                              {t(`account.${tab}`)} · {tab === 'sessions' ? sessions.length : devices.length}
                            </button>
                          ))}
                        </div>
                        {securityError && <p className="pg-acc-msg pg-acc-msg--err" role="alert">{securityError}</p>}

                        {securityTab === 'sessions'
                          ? (
                            visibleSessions.length === 0
                              ? <div className="pg-acc-empty">{t('account.noSessions')}</div>
                              : (
                                <ul className="pg-acc-rows">
                                  {visibleSessions.map(session => (
                                    <li key={session.sessionId} className="pg-acc-row">
                                      <div className="pg-acc-row-main">
                                        <span className="cx-chip">{session.clientId || '—'}</span>
                                        <span className="cx-chip">{session.kind || '—'}</span>
                                        <span className="cx-muted pg-acc-row-date">{formatDateTime(session.createdAt)}</span>
                                      </div>
                                      <button className="pg-acc-revoke" disabled={revoking} onClick={() => void revokeSession(session.sessionId)}>
                                        {t('account.revoke')}
                                      </button>
                                    </li>
                                  ))}
                                </ul>
                              )
                          )
                          : (
                            visibleDevices.length === 0
                              ? <div className="pg-acc-empty">{t('account.noDevices')}</div>
                              : (
                                <ul className="pg-acc-rows">
                                  {visibleDevices.map(device => (
                                    <li key={device.deviceId} className="pg-acc-row">
                                      <div className="pg-acc-row-main">
                                        <span className="pg-acc-row-name">{device.name || device.deviceId}</span>
                                        <span className="cx-chip">{device.platform || '—'}</span>
                                        {device.revoked && <span className="cx-chip cx-chip--error">{t('account.revoked')}</span>}
                                      </div>
                                      {!device.revoked && (
                                        <button className="pg-acc-revoke" disabled={revoking} onClick={() => void revokeDevice(device.deviceId)}>
                                          {t('account.revoke')}
                                        </button>
                                      )}
                                    </li>
                                  ))}
                                </ul>
                              )
                          )}
                        {pageCount > 1 && (
                          <div className="pg-acc-pagination">
                            <span className="cx-muted">{t('account.pageLabel', { current: currentPage + 1, total: pageCount })}</span>
                            <button
                              className="cx-btn cx-btn--outline cx-btn--sm"
                              disabled={currentPage === 0}
                              aria-label={t('account.previousPage')}
                              onClick={() => setSecurityPage(currentPage - 1)}
                            ><ChevronLeft size={14} /></button>
                            <button
                              className="cx-btn cx-btn--outline cx-btn--sm"
                              disabled={currentPage + 1 >= pageCount}
                              aria-label={t('account.nextPage')}
                              onClick={() => setSecurityPage(currentPage + 1)}
                            ><ChevronRight size={14} /></button>
                          </div>
                        )}
                      </section>
                    </FadeIn>
                  </div>

                  <div className="pg-acc-grid">
                    <FadeIn delay={0.18}>
                      <section className="cx-card pg-account-card">
                        <div className="pg-acc-card-head">
                          <h2 className="pg-acc-card-title">{t('account.myLibrary')}</h2>
                          <button
                            className="cx-btn cx-btn--text cx-btn--sm"
                            disabled={!storeWebBase}
                            onClick={() => openStoreWeb('/store/library')}
                          >
                            {t('account.viewInStore')}
                          </button>
                        </div>
                        <dl className="pg-acc-stats">
                          <div className="pg-acc-stat">
                            <dd>{library?.favorites?.length ?? 0}</dd>
                            <dt>{t('account.favoritesCount')}</dt>
                          </div>
                          <div className="pg-acc-stat">
                            <dd>{library?.entitlements?.length ?? 0}</dd>
                            <dt>{t('account.entitlementsCount')}</dt>
                          </div>
                          <div className="pg-acc-stat">
                            <dd>{library?.installHistory?.length ?? 0}</dd>
                            <dt>{t('account.installedCount')}</dt>
                          </div>
                        </dl>
                        {!library?.favorites?.length
                          ? <div className="pg-acc-empty">{t('account.noFavorites')}</div>
                          : (
                            <ul className="pg-acc-favs">
                              {library.favorites.slice(0, 3).map((favorite, index) => (
                                <li key={favorite.listingCoordinate ?? favorite.name ?? index}>
                                  <span className="pg-acc-fav-name">{favorite.name || favorite.listingCoordinate}</span>
                                  <span className="cx-muted pg-acc-fav-date">{favorite.addedAt?.slice(0, 10) || '—'}</span>
                                </li>
                              ))}
                            </ul>
                          )}
                      </section>
                    </FadeIn>

                    <FadeIn delay={0.22}>
                      <section className="cx-card pg-account-card">
                        <div className="pg-acc-card-head">
                          <h2 className="pg-acc-card-title">{t('account.myOrganizations')}</h2>
                          <button
                            className="cx-btn cx-btn--text cx-btn--sm"
                            disabled={!storeWebBase}
                            onClick={() => openStoreWeb('/store/organizations')}
                          >
                            {t('account.viewInStore')}
                          </button>
                        </div>
                        {organizations.length === 0
                          ? <div className="pg-acc-empty">{t('account.noOrganizations')}</div>
                          : (
                            <div className="pg-acc-chips">
                              {organizations.map((org, index) => (
                                <span key={org.organizationId ?? org.slug ?? index} className="cx-chip cx-chip--solid">
                                  {org.name || org.slug}
                                </span>
                              ))}
                            </div>
                          )}
                      </section>
                    </FadeIn>
                  </div>
                </>
              )}
      </div>
    </div>
  )
}

function statusOf(e: unknown): number | undefined {
  return (e as { response?: { status?: number } } | null)?.response?.status
}

function messageOf(e: unknown, t: (key: string) => string): string {
  // The http interceptor carries the backend's own {"error": "..."} text on the
  // AxiosError message, so this surfaces the real failure reason.
  return e instanceof Error && e.message ? e.message : t('common.unexpectedError')
}

function formatDateTime(iso?: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(i18n.global.locale.value || undefined)
}
