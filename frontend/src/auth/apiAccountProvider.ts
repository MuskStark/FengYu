import { api } from '@/api/client'
import type { AccountProvider, AccountUser } from '@/auth/accountProvider'

/**
 * API-backed account provider (design §7.2): sign-in drives the host's OAuth 2.1 +
 * PKCE browser flow against the Infinia Store; the renderer just polls the attempt
 * until the browser round-trip completes.
 */

const SIGN_IN_POLL_INTERVAL_MS = 1500
const SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000

function toAccountUser(view: {
  authenticated: boolean
  userId: string
  username: string
  email?: string | null
}): AccountUser {
  return {
    id: view.userId,
    username: view.username || view.userId,
    email: view.email ?? undefined,
    avatarUrl: undefined,
    authenticated: view.authenticated,
  }
}

export class ApiAccountProvider implements AccountProvider {
  async getCurrentUser(): Promise<AccountUser | null> {
    const view = await api.getAccount()
    return toAccountUser(view)
  }

  async signIn(): Promise<AccountUser> {
    const started = await api.startAccountSignIn()
    const deadline = Date.now() + SIGN_IN_TIMEOUT_MS
    // The host opens the system browser itself; poll the attempt until it lands.
    // eslint-disable-next-line no-constant-condition
    while (true) {
      if (Date.now() > deadline) {
        throw new Error('Sign-in timed out')
      }
      const attempt = await api.getAccountSignInStatus(started.attemptId)
      if (attempt.status === 'COMPLETED' && attempt.user) {
        return toAccountUser(attempt.user)
      }
      if (attempt.status === 'FAILED') {
        throw new Error(attempt.error || 'Sign-in failed')
      }
      await new Promise((resolve) => setTimeout(resolve, SIGN_IN_POLL_INTERVAL_MS))
    }
  }

  async signOut(): Promise<void> {
    await api.signOutAccount()
  }
}
