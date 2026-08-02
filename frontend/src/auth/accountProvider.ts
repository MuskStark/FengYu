export interface AccountUser {
  id: string
  username: string
  email?: string
  avatarUrl?: string
  authenticated: boolean
}

/**
 * Integration seam for a future backend-backed account system. The shell and
 * account pages consume this contract rather than depending on a login API.
 */
export interface AccountProvider {
  getCurrentUser(): Promise<AccountUser | null>
  signIn(): Promise<AccountUser>
  signOut(): Promise<void>
}

const localAccount: AccountUser = {
  id: 'local:summer',
  username: 'Summer',
  authenticated: false,
}

const localAccountProvider: AccountProvider = {
  async getCurrentUser() {
    return localAccount
  },
  async signIn() {
    throw new Error('No account sign-in provider has been configured')
  },
  async signOut() {
    // A local-only account has no server session to terminate.
  },
}

let accountProvider: AccountProvider = localAccountProvider

export function getAccountProvider(): AccountProvider {
  return accountProvider
}

/** Install an API-backed provider before the account store is first loaded. */
export function setAccountProvider(provider: AccountProvider): void {
  accountProvider = provider
}

export function resetAccountProvider(): void {
  accountProvider = localAccountProvider
}
