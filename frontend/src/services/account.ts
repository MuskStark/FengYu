/**
 * Account domain — the optional cloud account (sign-in, profile, library,
 * organizations, sessions, devices). All DTOs mirror their host-side records.
 */
import { http } from './impl/http'

/** Mirror of the host-side AccountView DTO (CloudAccountService.AccountView). */
export interface AccountView {
  authenticated: boolean
  userId: string
  username: string
  email?: string | null
  roles: string[]
}

export interface AccountSignInAttempt {
  status: 'PENDING' | 'COMPLETED' | 'FAILED'
  user?: AccountView | null
  error?: string | null
}

/**
 * Live store profile (StoreAuthGateway.StoreProfile proxied through
 * /api/account/store-profile): carries the Infinia Level (beeLevel 0-4) that
 * the DB-backed AccountView deliberately leaves out.
 */
export interface AccountStoreProfile {
  userId: string
  email?: string | null
  displayName?: string | null
  roles: string[]
  beeLevel: number
  createdAt?: string | null
}

/** Store-side library summary (StoreAccountGateway.Library). */
export interface AccountLibrary {
  favorites?: AccountFavorite[] | null
  entitlements?: AccountEntitlement[] | null
  installHistory?: AccountInstallEvent[] | null
}

export interface AccountFavorite {
  listingCoordinate?: string | null
  name?: string | null
  addedAt?: string | null
}

export interface AccountEntitlement {
  listingCoordinate?: string | null
  free?: boolean
  acquiredAt?: string | null
}

export interface AccountInstallEvent {
  coordinate?: string | null
  version?: string | null
  action?: string | null
  outcome?: string | null
  occurredAt?: string | null
}

export interface AccountOrganization {
  organizationId?: string | null
  slug?: string | null
  name?: string | null
}

export interface AccountSession {
  sessionId: string
  clientId?: string | null
  kind?: string | null
  createdAt?: string | null
}

export interface AccountDevice {
  deviceId: string
  name?: string | null
  platform?: string | null
  revoked?: boolean
}

export interface AccountPasswordResult {
  succeeded: boolean
  message?: string | null
}

export interface AccountService {
  me(): Promise<AccountView>
  startSignIn(): Promise<{ attemptId: string; authorizationUrl: string }>
  signInStatus(attemptId: string): Promise<AccountSignInAttempt>
  signOut(): Promise<AccountView>
  storeProfile(): Promise<AccountStoreProfile>
  updateProfile(displayName: string): Promise<AccountStoreProfile>
  changePassword(currentPassword: string, newPassword: string): Promise<AccountPasswordResult>
  library(): Promise<AccountLibrary>
  organizations(): Promise<AccountOrganization[]>
  sessions(): Promise<AccountSession[]>
  revokeSession(sessionId: string): Promise<void>
  devices(): Promise<AccountDevice[]>
  revokeDevice(deviceId: string): Promise<void>
}

export const accountService: AccountService = {
  me: () => http.get<AccountView>('/api/account/me').then((r) => r.data),
  startSignIn: () =>
    http.post<{ attemptId: string; authorizationUrl: string }>('/api/account/sign-in').then((r) => r.data),
  signInStatus: (attemptId) =>
    http.get<AccountSignInAttempt>(`/api/account/sign-in/${encodeURIComponent(attemptId)}`).then((r) => r.data),
  signOut: () => http.post<AccountView>('/api/account/sign-out').then((r) => r.data),
  storeProfile: () => http.get<AccountStoreProfile>('/api/account/store-profile').then((r) => r.data),
  updateProfile: (displayName) =>
    http.put<AccountStoreProfile>('/api/account/profile', { displayName }).then((r) => r.data),
  changePassword: (currentPassword, newPassword) =>
    http.put<AccountPasswordResult>('/api/account/password', { currentPassword, newPassword }).then((r) => r.data),
  library: () => http.get<AccountLibrary>('/api/account/library').then((r) => r.data),
  organizations: () => http.get<AccountOrganization[]>('/api/account/organizations').then((r) => r.data),
  sessions: () => http.get<AccountSession[]>('/api/account/sessions').then((r) => r.data),
  revokeSession: (sessionId) => http.delete(`/api/account/sessions/${encodeURIComponent(sessionId)}`),
  devices: () => http.get<AccountDevice[]>('/api/account/devices').then((r) => r.data),
  revokeDevice: (deviceId) => http.delete(`/api/account/devices/${encodeURIComponent(deviceId)}`),
}
