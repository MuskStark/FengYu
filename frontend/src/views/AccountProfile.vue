<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import type {
  AccountDevice,
  AccountLibrary,
  AccountOrganization,
  AccountSession,
  AccountStoreProfile,
} from '@/api/client'
import { useAccountStore } from '@/stores/account'
import { openExternalUrl } from '@/mf/desktop'
import BeeLevelBadge from '@/components/account/BeeLevelBadge.vue'

/**
 * User center — the desktop mirror of the store platform's account page: one
 * signed-in landing view aggregating identity with the Infinia Level badge,
 * profile editing, library/organization summaries and account security
 * (password, sessions, devices). Signed out, it degrades to a local-account
 * card that starts the browser OAuth flow. Store data is always fetched live
 * through the loopback proxy; only the fast identity (/api/account/me) is
 * DB-backed, so a store outage never blocks the shell.
 */
const { t } = useI18n()
const router = useRouter()
const account = useAccountStore()

const profile = ref<AccountStoreProfile | null>(null)
const library = ref<AccountLibrary | null>(null)
const organizations = ref<AccountOrganization[]>([])
const sessions = ref<AccountSession[]>([])
const devices = ref<AccountDevice[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const storeWebBase = ref<string | null>(null)

// sign-in / sign-out
const busy = ref(false)
const signInError = ref<string | null>(null)

// profile editing
const displayNameDraft = ref('')
const savingProfile = ref(false)
const profileMessage = ref<string | null>(null)
const profileError = ref<string | null>(null)

// password
const currentPassword = ref('')
const newPassword = ref('')
const savingPassword = ref(false)
const passwordMessage = ref<string | null>(null)
const passwordError = ref<string | null>(null)

// session / device revocation
const revoking = ref(false)
const securityError = ref<string | null>(null)

const securityTab = ref<'sessions' | 'devices'>('sessions')
const securityPage = ref(0)
const pageCount = computed(() => Math.max(1, Math.ceil((securityTab.value === 'sessions' ? sessions.value.length : devices.value.length) / 3)))
const currentPage = computed(() => Math.min(securityPage.value, pageCount.value - 1))
const visibleSessions = computed(() => [...sessions.value].sort((a, b) => Date.parse(b.createdAt ?? '') - Date.parse(a.createdAt ?? '')).slice(currentPage.value * 3, currentPage.value * 3 + 3))
const visibleDevices = computed(() => devices.value.slice(currentPage.value * 3, currentPage.value * 3 + 3))
watch(securityTab, () => { securityPage.value = 0 })

const beeLevel = computed(() => profile.value?.beeLevel ?? 0)
const nextLevel = computed(() => (beeLevel.value < 4 ? beeLevel.value + 1 : null))
const initials = computed(() =>
  (profile.value?.displayName || profile.value?.email || t('account.defaultName')).trim().charAt(0).toUpperCase() || 'U',
)
const roles = computed(() => profile.value?.roles ?? [])
const canSaveProfile = computed(() => {
  const name = displayNameDraft.value.trim()
  return name.length > 0 && name.length <= 64 && name !== (profile.value?.displayName ?? '')
})
const canChangePassword = computed(
  () => currentPassword.value.length > 0 && newPassword.value.length >= 8,
)
const showSkeleton = computed(
  () => !account.loaded || (account.isAuthenticated && loading.value && !profile.value),
)

const quickLinks = computed(() => {
  const links: { key: string; to?: string; external?: string }[] = [
    { key: 'account.storePage', to: '/store' },
    { key: 'account.myLibrary', external: '/store/library' },
    { key: 'account.myOrganizations', external: '/store/organizations' },
    { key: 'account.manageOnline', external: '/store/account' },
  ]
  if (roles.value.some((r) => ['PUBLISHER', 'ORG_ADMIN', 'REVIEWER'].includes(r))) {
    links.push({ key: 'account.publisherCenter', external: '/publisher' })
  }
  if (roles.value.includes('PLATFORM_ADMIN')) {
    links.push({ key: 'account.adminConsole', external: '/admin' })
  }
  return links
})

onMounted(() => {
  if (!account.loaded) void account.load()
})

// The center loads whenever the shell flips into (or out of) a cloud session:
// after the browser OAuth round-trip, on sign-out, and on first mount when the
// sidebar already resolved /api/account/me.
watch(
  () => account.isAuthenticated,
  (authed) => {
    if (authed) {
      void reload()
    } else {
      resetCenter()
    }
  },
  { immediate: true },
)

function resetCenter() {
  securityTab.value = 'sessions'
  securityPage.value = 0
  profile.value = null
  library.value = null
  organizations.value = []
  sessions.value = []
  devices.value = []
  loadError.value = null
  storeWebBase.value = null
  displayNameDraft.value = ''
  currentPassword.value = ''
  newPassword.value = ''
  profileMessage.value = null
  profileError.value = null
  passwordMessage.value = null
  passwordError.value = null
  securityError.value = null
}

async function reload() {
  if (loading.value) return
  loading.value = true
  loadError.value = null
  try {
    const [me, lib, activeSessions, activeDevices, status] = await Promise.all([
      api.getAccountStoreProfile(),
      api.getAccountLibrary(),
      api.getAccountSessions(),
      api.getAccountDevices(),
      api.getStoreStatus().catch(() => null),
    ])
    profile.value = me
    displayNameDraft.value = me.displayName ?? ''
    library.value = lib
    sessions.value = activeSessions
    devices.value = activeDevices
    storeWebBase.value = status?.apiBase?.replace(/\/+$/, '') ?? null
    try {
      organizations.value = await api.getAccountOrganizations()
    } catch {
      organizations.value = [] // memberships are optional context, never fatal
    }
  } catch (e) {
    profile.value = null
    if (isUnauthorized(e)) {
      // A dead cloud session (expired public-client token, rejected refresh) makes
      // the host drop the binding — re-read /api/account/me so the shell falls
      // back to the local-account view instead of a dead-end error card.
      await account.load()
      if (!account.isAuthenticated) return
    }
    loadError.value = messageOf(e)
  } finally {
    loading.value = false
  }
}

function isUnauthorized(e: unknown): boolean {
  return (e as { response?: { status?: number } } | null)?.response?.status === 401
}

// Cancels the sign-in poll loop when the page is left mid-flow — without it the
// provider keeps polling the loopback attempt for its full 5-minute window.
let signInAbort: AbortController | null = null
onBeforeUnmount(() => signInAbort?.abort())

async function signIn() {
  if (busy.value) return
  busy.value = true
  signInError.value = null
  signInAbort = new AbortController()
  try {
    await account.signIn({ signal: signInAbort.signal })
  } catch (e) {
    if (!(e instanceof DOMException && e.name === 'AbortError')) {
      signInError.value = messageOf(e)
    }
  } finally {
    signInAbort = null
    busy.value = false
  }
}

async function signOut() {
  if (busy.value) return
  busy.value = true
  try {
    await account.signOut()
  } finally {
    busy.value = false
  }
}

async function saveProfile() {
  if (!canSaveProfile.value || savingProfile.value) return
  savingProfile.value = true
  profileMessage.value = null
  profileError.value = null
  try {
    profile.value = await api.updateAccountProfile(displayNameDraft.value.trim())
    // Keep the shell (sidebar) in step with the store-side rename.
    if (account.user) {
      account.user = {
        ...account.user,
        username: profile.value.displayName || account.user.username,
      }
    }
    profileMessage.value = t('account.profileSaved')
  } catch (e) {
    profileError.value = messageOf(e)
  } finally {
    savingProfile.value = false
  }
}

async function changePassword() {
  if (!canChangePassword.value || savingPassword.value) return
  savingPassword.value = true
  passwordMessage.value = null
  passwordError.value = null
  try {
    const result = await api.changeAccountPassword(currentPassword.value, newPassword.value)
    passwordMessage.value = result.message || t('account.passwordChanged')
    currentPassword.value = ''
    newPassword.value = ''
  } catch (e) {
    passwordError.value = messageOf(e)
  } finally {
    savingPassword.value = false
  }
}

async function revokeSession(sessionId: string) {
  if (revoking.value) return
  revoking.value = true
  securityError.value = null
  try {
    await api.revokeAccountSession(sessionId)
    sessions.value = sessions.value.filter((s) => s.sessionId !== sessionId)
  } catch (e) {
    securityError.value = messageOf(e)
  } finally {
    revoking.value = false
  }
}

async function revokeDevice(deviceId: string) {
  if (revoking.value) return
  revoking.value = true
  securityError.value = null
  try {
    await api.revokeAccountDevice(deviceId)
    devices.value = devices.value.map((d) =>
      d.deviceId === deviceId ? { ...d, revoked: true } : d,
    )
  } catch (e) {
    securityError.value = messageOf(e)
  } finally {
    revoking.value = false
  }
}

function openStoreWeb(path: string) {
  if (!storeWebBase.value) return
  void openExternalUrl(storeWebBase.value + path).catch(() => {
    securityError.value = t('common.unexpectedError')
  })
}

function roleLabel(role: string) {
  const key = `account.roleLabels.${role}`
  const translated = t(key)
  return translated === key ? role : translated
}

function formatDateTime(iso?: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString()
}

function messageOf(e: unknown): string {
  // The axios interceptor carries the backend's {"error": "..."} text on the
  // AxiosError message, so this already surfaces the real failure reason.
  return e instanceof Error && e.message ? e.message : t('common.unexpectedError')
}
</script>

<template>
  <div class="account-page-scroll">
    <div class="account-page">
      <div class="account-heading"><div><h1 class="cx-page-title">{{ t('account.title') }}</h1><p class="cx-muted">{{ t('account.overviewHint') }}</p></div><button v-if="profile" class="cx-btn cx-btn--outline account-signout" :disabled="busy" @click="signOut">{{ t('account.signOut') }}</button></div>

      <div v-if="showSkeleton" class="account-skeleton" aria-busy="true">
        <div class="account-skeleton-block account-skeleton-wide"></div>
        <div class="account-skeleton-block"></div>
        <div class="account-skeleton-block"></div>
        <div class="account-skeleton-block"></div>
        <div class="account-skeleton-block"></div>
      </div>

      <!-- Local account: start the browser OAuth flow -->
      <div v-else-if="!account.isAuthenticated" class="cx-card account-signin-card">
        <div class="account-avatar" aria-hidden="true">{{ account.initials }}</div>
        <div class="cx-grow">
          <div class="account-name">{{ account.displayName }}</div>
          <div class="account-signin-chiprow">
            <span class="cx-chip">{{ t('account.localAccount') }}</span>
          </div>
          <p class="account-signin-hint cx-muted">{{ t('account.signInHint') }}</p>
          <p v-if="signInError" class="account-form-msg err" role="alert">{{ signInError }}</p>
        </div>
        <div class="account-signin-action">
          <button class="cx-btn cx-btn--primary" :disabled="busy" @click="signIn">
            <span v-if="busy" class="cx-spin"></span>
            {{ t('account.signIn') }}
          </button>
          <p v-if="busy" class="account-signin-pending cx-muted">{{ t('account.signInPending') }}</p>
        </div>
      </div>

      <div v-else-if="loadError" class="cx-alert cx-alert--error" role="alert">
        <div class="cx-alert__body">{{ loadError }}</div>
        <!-- Escape hatch: a signed-in user whose store profile cannot load (store
             down, session dead) must always be able to re-sign-in or sign out —
             this card is the only surface they see. -->
        <div class="account-error-actions">
          <button class="cx-btn cx-btn--outline cx-btn--sm" @click="reload">
            {{ t('common.retry') }}
          </button>
          <button class="cx-btn cx-btn--outline cx-btn--sm" :disabled="busy" @click="signIn">
            {{ t('account.signIn') }}
          </button>
          <button
            class="cx-btn cx-btn--outline cx-btn--sm account-signout"
            :disabled="busy"
            @click="signOut"
          >
            {{ t('account.signOut') }}
          </button>
        </div>
      </div>

      <template v-else-if="profile">
        <div class="account-hero">
          <section class="cx-card account-passport">
            <div class="account-identity">
              <div class="account-ov-row">
                <div class="account-avatar" aria-hidden="true">{{ initials }}</div>
                <div class="cx-grow account-ov-id">
                  <h2 class="account-name">{{ profile.displayName || profile.userId }}</h2>
                  <div class="cx-muted account-email">{{ profile.email }}</div>
                  <div class="account-chips">
                    <span v-for="role in roles.length ? roles : ['USER']" :key="role" class="cx-chip">{{ roleLabel(role) }}</span>
                  </div>
                </div>
              </div>
              <dl class="account-stats account-hero-stats">
                <div class="account-stat"><dd>{{ library?.entitlements?.length ?? 0 }}</dd><dt>{{ t('account.entitlementsCount') }}</dt></div>
                <div class="account-stat"><dd>{{ devices.length }}</dd><dt>{{ t('account.devices') }}</dt></div>
                <div class="account-stat"><dd>{{ sessions.length }}</dd><dt>{{ t('account.sessions') }}</dt></div>
              </dl>
            </div>
            <div class="account-membership">
              <svg class="account-honeycomb" viewBox="0 0 160 160" fill="none" stroke="currentColor" aria-hidden="true"><path d="M80 10 115 30v40L80 90 45 70V30Z M115 70l35 20v40l-35 20-35-20V90 M45 70l35 20v40l-35 20-35-20V90" /></svg>
              <p class="account-eyebrow">INFINIA · MEMBERSHIP</p>
              <div class="account-level-line"><BeeLevelBadge :level="beeLevel" /><span class="cx-muted account-level-hint">{{ nextLevel !== null ? t('account.levelNext', { next: t(`account.beeLevel.${nextLevel}`) }) : t('account.levelTop') }}</span></div>
              <div class="account-level-track" aria-hidden="true"><span v-for="level in 5" :key="level" :class="{ active: level - 1 <= beeLevel }"></span></div>
            </div>
          </section>
          <nav class="cx-card account-access" :aria-label="t('account.quickLinks')">
            <h2 class="account-eyebrow cx-muted">{{ t('account.quickLinks') }}</h2>
            <button v-for="link in quickLinks" :key="link.key" class="account-access-link" :disabled="!link.to && !storeWebBase" @click="link.to ? router.push(link.to) : openStoreWeb(link.external ?? '')"><span>{{ t(link.key) }}</span><span aria-hidden="true">↗</span></button>
          </nav>
        </div>

        <!-- Everything below the overview lives in a compact two-column grid. -->
        <div class="account-grid">
          <!-- Profile editing -->
          <section class="cx-card">
            <h2 class="account-card-title">{{ t('account.editProfile') }}</h2>
            <p class="account-description cx-muted">{{ t('account.profileHint') }}</p>
            <form class="account-form" @submit.prevent="saveProfile">
              <label class="cx-field cx-grow">
                <span class="cx-label">{{ t('account.displayName') }}</span>
                <input
                  v-model="displayNameDraft"
                  class="cx-input"
                  maxlength="64"
                  :disabled="savingProfile"
                />
              </label>
              <button
                type="submit"
                class="cx-btn cx-btn--primary"
                :disabled="!canSaveProfile || savingProfile"
              >
                {{ t('common.confirm') }}
              </button>
            </form>
            <p v-if="profileMessage" class="account-form-msg ok" role="status">{{ profileMessage }}</p>
            <p v-if="profileError" class="account-form-msg err" role="alert">{{ profileError }}</p>
            <hr class="account-divider" />
            <h3 class="account-card-title">{{ t('account.changePassword') }}</h3>
            <form class="account-form account-form--stack" @submit.prevent="changePassword">
              <label class="cx-field">
                <span class="cx-label">{{ t('account.currentPassword') }}</span>
                <input
                  v-model="currentPassword"
                  type="password"
                  class="cx-input"
                  autocomplete="current-password"
                  :disabled="savingPassword"
                />
              </label>
              <label class="cx-field">
                <span class="cx-label">{{ t('account.newPassword') }}</span>
                <input
                  v-model="newPassword"
                  type="password"
                  class="cx-input"
                  minlength="8"
                  maxlength="128"
                  autocomplete="new-password"
                  :disabled="savingPassword"
                />
              </label>
              <button
                type="submit"
                class="cx-btn cx-btn--primary"
                :disabled="!canChangePassword || savingPassword"
              >
                {{ t('account.changePassword') }}
              </button>
            </form>
            <p v-if="passwordMessage" class="account-form-msg ok" role="status">{{ passwordMessage }}</p>
            <p v-if="passwordError" class="account-form-msg err" role="alert">{{ passwordError }}</p>
          </section>
          <section class="cx-card">
            <h2 class="account-card-title">{{ t('account.signinDevices') }}</h2>
            <p class="account-description cx-muted">{{ t('account.securityHint') }}</p>
            <div class="account-tabs" role="group" :aria-label="t('account.signinDevices')">
              <button v-for="tab in (['sessions', 'devices'] as const)" :key="tab" :aria-pressed="securityTab === tab" @click="securityTab = tab">{{ t(`account.${tab}`) }} · {{ tab === 'sessions' ? sessions.length : devices.length }}</button>
            </div>
            <p v-if="securityError" class="account-form-msg err" role="alert">{{ securityError }}</p>

            <div v-if="securityTab === 'sessions'" class="account-fold">
              <div class="cx-details__body">
                <div v-if="!sessions.length" class="account-empty">
                  {{ t('account.noSessions') }}
                </div>
                <ul v-else class="account-rows">
                  <li v-for="session in visibleSessions" :key="session.sessionId" class="account-row">
                    <div class="account-row-main">
                      <span class="cx-chip">{{ session.clientId || '—' }}</span>
                      <span class="cx-chip">{{ session.kind || '—' }}</span>
                      <span class="cx-muted account-row-date">
                        {{ formatDateTime(session.createdAt) }}
                      </span>
                    </div>
                    <button
                      class="account-revoke"
                      :disabled="revoking"
                      @click="revokeSession(session.sessionId)"
                    >
                      {{ t('account.revoke') }}
                    </button>
                  </li>
                </ul>
              </div>
            </div>

            <div v-else class="account-fold">
              <div class="cx-details__body">
                <div v-if="!devices.length" class="account-empty">{{ t('account.noDevices') }}</div>
                <ul v-else class="account-rows">
                  <li v-for="device in visibleDevices" :key="device.deviceId" class="account-row">
                    <div class="account-row-main">
                      <span class="account-row-name">{{ device.name || device.deviceId }}</span>
                      <span class="cx-chip">{{ device.platform || '—' }}</span>
                      <span v-if="device.revoked" class="cx-chip cx-chip--error">
                        {{ t('account.revoked') }}
                      </span>
                    </div>
                    <button
                      v-if="!device.revoked"
                      class="account-revoke"
                      :disabled="revoking"
                      @click="revokeDevice(device.deviceId)"
                    >
                      {{ t('account.revoke') }}
                    </button>
                  </li>
                </ul>
              </div>
            </div>
            <div v-if="pageCount > 1" class="account-pagination">
              <span class="cx-muted">{{ t('account.pageLabel', { current: currentPage + 1, total: pageCount }) }}</span>
              <button class="cx-btn cx-btn--outline" :disabled="currentPage === 0" :aria-label="t('account.previousPage')" @click="securityPage = currentPage - 1">←</button>
              <button class="cx-btn cx-btn--outline" :disabled="currentPage + 1 >= pageCount" :aria-label="t('account.nextPage')" @click="securityPage = currentPage + 1">→</button>
            </div>
          </section>
        </div>
        <div class="account-grid">
          <!-- Library summary -->
          <section class="cx-card">
            <div class="account-card-head">
              <h2 class="account-card-title">{{ t('account.myLibrary') }}</h2>
              <button
                class="cx-btn cx-btn--text cx-btn--sm"
                :disabled="!storeWebBase"
                @click="openStoreWeb('/store/library')"
              >
                {{ t('account.viewInStore') }}
              </button>
            </div>
            <dl class="account-stats">
              <div class="account-stat">
                <dd>{{ library?.favorites?.length ?? 0 }}</dd>
                <dt>{{ t('account.favoritesCount') }}</dt>
              </div>
              <div class="account-stat">
                <dd>{{ library?.entitlements?.length ?? 0 }}</dd>
                <dt>{{ t('account.entitlementsCount') }}</dt>
              </div>
              <div class="account-stat">
                <dd>{{ library?.installHistory?.length ?? 0 }}</dd>
                <dt>{{ t('account.installedCount') }}</dt>
              </div>
            </dl>
            <div v-if="!(library?.favorites?.length)" class="account-empty">
              {{ t('account.noFavorites') }}
            </div>
            <ul v-else class="account-favs">
              <li
                v-for="(favorite, index) in (library?.favorites ?? []).slice(0, 3)"
                :key="favorite.listingCoordinate ?? favorite.name ?? index"
              >
                <span class="account-fav-name">{{ favorite.name || favorite.listingCoordinate }}</span>
                <span class="cx-muted account-fav-date">{{ favorite.addedAt?.slice(0, 10) || '—' }}</span>
              </li>
            </ul>
          </section>

          <!-- Organizations summary -->
          <section class="cx-card">
            <div class="account-card-head">
              <h2 class="account-card-title">{{ t('account.myOrganizations') }}</h2>
              <button
                class="cx-btn cx-btn--text cx-btn--sm"
                :disabled="!storeWebBase"
                @click="openStoreWeb('/store/organizations')"
              >
                {{ t('account.viewInStore') }}
              </button>
            </div>
            <div v-if="!organizations.length" class="account-empty">
              {{ t('account.noOrganizations') }}
            </div>
            <div v-else class="account-chips">
              <span
                v-for="(org, index) in organizations"
                :key="org.organizationId ?? org.slug ?? index"
                class="cx-chip cx-chip--solid"
              >
                {{ org.name || org.slug }}
              </span>
            </div>
          </section>

        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.account-page-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
.account-page { max-width: 900px; margin: 0 auto; padding: 28px 24px 48px; }

/* ── shared bits ──────────────────────────────────────────────── */
.account-avatar {
  width: 56px;
  height: 56px;
  border-radius: var(--cx-radius-lg);
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  background: var(--cx-hover-strong);
  font-size: 22px;
  font-weight: 700;
}
.account-name { font-size: 18px; font-weight: 650; }
.account-email { font-size: 13px; margin-top: 2px; }
.account-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.account-card-title { font-size: 14px; font-weight: 650; margin: 0 0 12px; }
.account-card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 12px; }
.account-card-head .account-card-title { margin: 0; }
.account-empty {
  border: 1px dashed var(--cx-border);
  border-radius: var(--cx-radius);
  padding: 20px 12px;
  margin-top: 10px;
  text-align: center;
  font-size: 13px;
  color: rgb(var(--v-theme-secondary));
}

/* ── overview card ────────────────────────────────────────────── */
.account-ov-row { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 18px; }
.account-ov-id { min-width: 220px; }
.account-signout { flex: 0 0 auto; }
.account-signout:hover:not(:disabled) {
  color: rgb(var(--v-theme-error));
  border-color: rgb(var(--v-theme-error));
  background: transparent;
}
/* ── two-column grid ──────────────────────────────────────────── */
.account-grid { display: grid; grid-template-columns: 1fr; gap: 14px; margin-top: 14px; }
@media (min-width: 860px) { .account-grid { grid-template-columns: 1fr 1fr; } }

/* ── forms ────────────────────────────────────────────────────── */
.account-form { display: flex; align-items: flex-end; gap: 10px; }
.account-form .cx-btn { flex: 0 0 auto; }
.account-form-msg { margin: 8px 0 0; font-size: 12px; overflow-wrap: anywhere; }
.account-form-msg.ok { color: rgb(var(--v-theme-tertiary)); }
.account-form-msg.err { color: rgb(var(--v-theme-error)); }

/* ── library stats ────────────────────────────────────────────── */
.account-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 0; }
.account-stat {
  border: 1px solid var(--cx-border);
  border-radius: var(--cx-radius);
  padding: 10px 6px;
  text-align: center;
}
.account-stat dd { margin: 0; font-size: 22px; font-weight: 700; }
.account-stat dt { font-size: 11px; color: rgb(var(--v-theme-secondary)); }
.account-favs { list-style: none; margin: 10px 0 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.account-favs li { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 13px; }
.account-fav-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-fav-date { flex: 0 0 auto; font-size: 12px; }

/* ── security folds ───────────────────────────────────────────── */
.account-fold { margin-top: 12px; }
.account-rows { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.account-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border: 1px solid var(--cx-border);
  border-radius: var(--cx-radius);
  padding: 6px 10px;
}
.account-row-main { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; min-width: 0; }
.account-row-name { font-size: 13px; font-weight: 550; }
.account-row-date { font-size: 12px; }
.account-revoke {
  flex: 0 0 auto;
  padding: 3px 9px;
  border: 1px solid var(--cx-border);
  border-radius: var(--cx-radius-sm);
  background: transparent;
  color: rgb(var(--v-theme-error));
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color 0.13s ease;
}
.account-revoke:hover:not(:disabled) { border-color: rgb(var(--v-theme-error)); }
.account-revoke:disabled { opacity: 0.45; cursor: default; }

/* ── sign-in card (local account) ─────────────────────────────── */
.account-signin-card { display: flex; flex-wrap: wrap; align-items: center; gap: 16px; }
.account-signin-chiprow { margin-top: 8px; }
.account-signin-hint { margin: 8px 0 0; font-size: 12.5px; max-width: 420px; }
.account-signin-action { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.account-signin-pending { font-size: 12px; margin: 0; }

/* ── error card actions ──────────────────────────────────────── */
.account-error-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }

/* ── skeleton ─────────────────────────────────────────────────── */
.account-skeleton { display: grid; grid-template-columns: 1fr; gap: 14px; }
@media (min-width: 860px) { .account-skeleton { grid-template-columns: 1fr 1fr; } }
.account-skeleton-block {
  height: 170px;
  border: 1px solid var(--cx-border);
  border-radius: var(--cx-radius-lg);
  background: var(--cx-hover-strong);
  animation: account-pulse 1.4s ease-in-out infinite;
}
.account-skeleton-wide { grid-column: 1 / -1; height: 190px; }
@keyframes account-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.45; } }

/* Store account dashboard: passport, quick access and paired management cards. */
.account-page { max-width: 1440px; padding: 32px 28px 48px; }
.account-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 24px; }
.account-heading .cx-page-title { margin-bottom: 6px; }
.account-heading p { font-size: 14px; margin: 0; }
.account-page .cx-card { border-radius: 16px; padding: 28px; min-width: 0; }
.account-hero { display: grid; gap: 20px; }
.account-page .account-passport { display: grid; padding: 0; overflow: hidden; }
.account-identity { padding: 32px; display: flex; flex-direction: column; justify-content: space-between; gap: 32px; min-width: 0; }
.account-ov-row { flex-wrap: nowrap; gap: 16px; align-items: center; }
.account-ov-id { min-width: 0; }
.account-name { font-size: 24px; margin: 0; overflow-wrap: anywhere; }
.account-email { overflow-wrap: anywhere; margin: 4px 0 12px; }
.account-avatar { width: 64px; height: 64px; border-radius: 16px; color: rgb(var(--v-theme-primary)); background: rgba(var(--v-theme-primary), .1); border: 1px solid rgba(var(--v-theme-primary), .2); }
.account-hero-stats { border-top: 1px solid var(--cx-border); padding-top: 20px; gap: 0; }
.account-hero-stats .account-stat { border: 0; border-radius: 0; text-align: left; padding: 0 16px; }
.account-hero-stats .account-stat:first-child { padding-left: 0; }
.account-hero-stats .account-stat + .account-stat { border-left: 1px solid var(--cx-border); }
.account-membership { position: relative; isolation: isolate; overflow: hidden; padding: 32px; display: flex; flex-direction: column; justify-content: center; gap: 28px; background: var(--cx-hover); border-top: 1px solid var(--cx-border); }
.account-honeycomb { position: absolute; width: 190px; right: -40px; top: -32px; opacity: .15; z-index: -1; color: rgb(var(--v-theme-primary)); }
.account-eyebrow { font-size: 12px; font-weight: 650; letter-spacing: .15em; margin: 0; color: rgb(var(--v-theme-primary)); }
.account-level-line { margin: 0; gap: 12px; }
.account-level-track { display: flex; gap: 8px; }
.account-level-track span { height: 6px; flex: 1; border-radius: 8px; background: rgba(var(--v-theme-primary), .1); }
.account-level-track .active { background: rgba(var(--v-theme-primary), .7); }
.account-access { display: flex; flex-direction: column; gap: 8px; }
.account-access-link { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex: 1; padding: 12px 8px; border-radius: 12px; font-size: 14px; text-align: left; }
.account-access-link:hover:not(:disabled) { background: var(--cx-hover); }
.account-access-link:disabled { opacity: .45; }
.account-grid { gap: 20px; margin-top: 20px; }
.account-card-title { font-size: 16px; }
.account-description { font-size: 12px; line-height: 1.7; margin: 0 0 20px; }
.account-form { display: grid; gap: 16px; }
.account-form .cx-btn { justify-self: end; min-height: 44px; min-width: 160px; }
.account-divider { border: 0; border-top: 1px solid var(--cx-border); margin: 24px 0; }
.account-tabs { display: flex; border-bottom: 1px solid var(--cx-border); gap: 20px; }
.account-tabs button { padding: 12px 0; font-size: 13px; border-bottom: 2px solid transparent; }
.account-tabs button[aria-pressed="true"] { color: rgb(var(--v-theme-primary)); border-color: currentColor; }
.account-row { border: 0; border-radius: 0; border-bottom: 1px solid var(--cx-border); padding: 18px 0; }
.account-row-name { overflow-wrap: anywhere; }
.account-empty { border: 0; background: var(--cx-hover); border-radius: 12px; padding: 28px 16px; }
.account-pagination { display: flex; align-items: center; gap: 8px; margin-top: 16px; font-size: 12px; }
.account-pagination > span { margin-right: auto; }
@media (min-width: 600px) { .account-form--stack { grid-template-columns: 1fr 1fr; } .account-form--stack .cx-btn { grid-column: 1 / -1; } }
@media (min-width: 1000px) { .account-passport { grid-template-columns: 1fr 1fr; } .account-membership { border-top: 0; border-left: 1px solid var(--cx-border); } }
@media (min-width: 1280px) { .account-hero { grid-template-columns: minmax(0, 1fr) 240px; } }
@media (max-width: 599px) { .account-page { padding: 20px 16px 32px; } .account-page .cx-card, .account-identity, .account-membership { padding: 20px; } .account-page .account-passport { padding: 0; } .account-heading { align-items: flex-start; } .account-name { font-size: 20px; } }
@media (prefers-reduced-motion: reduce) { .account-skeleton-block { animation: none; } }
</style>
