import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  resetAccountProvider,
  setAccountProvider,
  type AccountProvider,
  type AccountUser,
} from '@/auth/accountProvider'
import { useAccountStore } from './account'

const signedInUser: AccountUser = {
  id: 'user:summer',
  username: 'Summer',
  email: 'summer@example.com',
  authenticated: true,
}

describe('account store provider boundary', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => resetAccountProvider())

  it('loads identity through the configured provider', async () => {
    const provider: AccountProvider = {
      async getCurrentUser() { return signedInUser },
      async signIn() { return signedInUser },
      async signOut() {},
    }
    setAccountProvider(provider)

    const store = useAccountStore()
    await store.load()

    expect(store.user).toEqual(signedInUser)
    expect(store.displayName).toBe('Summer')
    expect(store.initials).toBe('S')
    expect(store.isAuthenticated).toBe(true)
  })

  it('refreshes the current identity after sign-out', async () => {
    let current: AccountUser | null = signedInUser
    setAccountProvider({
      async getCurrentUser() { return current },
      async signIn() { current = signedInUser; return signedInUser },
      async signOut() { current = null },
    })

    const store = useAccountStore()
    await store.load()
    await store.signOut()

    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })
})
