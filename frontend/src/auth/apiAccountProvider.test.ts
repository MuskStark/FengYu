import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  startAccountSignIn: vi.fn(),
  getAccountSignInStatus: vi.fn(),
  openExternalUrl: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  api: {
    startAccountSignIn: mocks.startAccountSignIn,
    getAccountSignInStatus: mocks.getAccountSignInStatus,
  },
}))
vi.mock('@/mf/desktop', () => ({ openExternalUrl: mocks.openExternalUrl }))

import { ApiAccountProvider } from './apiAccountProvider'

describe('ApiAccountProvider sign-in', () => {
  beforeEach(() => vi.clearAllMocks())

  it('opens the Store authorization URL before polling the loopback attempt', async () => {
    mocks.startAccountSignIn.mockResolvedValue({
      attemptId: 'attempt-1',
      authorizationUrl: 'http://localhost:8080/oauth2/authorize?state=test',
    })
    mocks.openExternalUrl.mockResolvedValue(undefined)
    mocks.getAccountSignInStatus.mockResolvedValue({
      status: 'COMPLETED',
      user: {
        authenticated: true,
        userId: 'user-1',
        username: 'Summer',
        email: 'summer@example.com',
      },
    })

    const user = await new ApiAccountProvider().signIn()

    expect(mocks.openExternalUrl).toHaveBeenCalledWith(
      'http://localhost:8080/oauth2/authorize?state=test',
    )
    expect(mocks.openExternalUrl.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.getAccountSignInStatus.mock.invocationCallOrder[0],
    )
    expect(user).toMatchObject({ id: 'user-1', authenticated: true })
  })

  it('does not poll when the authorization page cannot be opened', async () => {
    mocks.startAccountSignIn.mockResolvedValue({
      attemptId: 'attempt-2',
      authorizationUrl: 'http://localhost:8080/oauth2/authorize',
    })
    mocks.openExternalUrl.mockRejectedValue(new Error('open failed'))

    await expect(new ApiAccountProvider().signIn()).rejects.toThrow('open failed')
    expect(mocks.getAccountSignInStatus).not.toHaveBeenCalled()
  })
})
