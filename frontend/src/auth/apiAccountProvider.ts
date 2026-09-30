import { api } from '@/api/client'
import type { AccountProvider, AccountUser } from '@/auth/accountProvider'
import { openExternalUrl } from '@/mf/desktop'

/**
 * API-backed account provider (design §7.2): sign-in drives the host's OAuth 2.1 +
 * PKCE browser flow against the Infinia Store; the renderer opens the returned
 * authorization URL and polls the attempt until the browser round-trip completes.
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

/** Cancellable sleep: resolves after `ms`, or rejects immediately when `signal` aborts. */
function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Sign-in cancelled', 'AbortError'))
      return
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(timer)
      reject(new DOMException('Sign-in cancelled', 'AbortError'))
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

export class ApiAccountProvider implements AccountProvider {
  async getCurrentUser(): Promise<AccountUser | null> {
    const view = await api.getAccount()
    return toAccountUser(view)
  }

  async signIn(options?: { signal?: AbortSignal }): Promise<AccountUser> {
    const signal = options?.signal
    const started = await api.startAccountSignIn()
    await openExternalUrl(started.authorizationUrl)
    const deadline = Date.now() + SIGN_IN_TIMEOUT_MS
    // The headless host owns the loopback listener; poll until the browser lands there.
    // The loop is abortable so leaving the sign-in page mid-flow cancels the polling
    // instead of keeping it alive for the full 5-minute window.
    // eslint-disable-next-line no-constant-condition
    while (true) {
      if (signal?.aborted) throw new DOMException('Sign-in cancelled', 'AbortError')
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
      await sleep(SIGN_IN_POLL_INTERVAL_MS, signal)
    }
  }

  async signOut(): Promise<void> {
    await api.signOutAccount()
  }
}
