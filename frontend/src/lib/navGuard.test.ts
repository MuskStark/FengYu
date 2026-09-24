import { describe, expect, it, vi } from 'vitest'
import { checkNavigationGuard, setNavigationGuard } from './navGuard'

describe('navigation guard', () => {
  it('allows everything while no guard is registered', async () => {
    await expect(checkNavigationGuard()).resolves.toBe(true)
  })

  it('consults the registered guard and propagates its verdict', async () => {
    const guard = vi.fn(async () => false)
    const dispose = setNavigationGuard(guard)

    await expect(checkNavigationGuard()).resolves.toBe(false)
    expect(guard).toHaveBeenCalledOnce()
    dispose()
  })

  it('restores allow-all after disposal', async () => {
    const dispose = setNavigationGuard(async () => false)
    dispose()
    await expect(checkNavigationGuard()).resolves.toBe(true)
  })

  it('a stale disposer does not uninstall a newer guard', async () => {
    const disposeFirst = setNavigationGuard(async () => false)
    const disposeSecond = setNavigationGuard(async () => true)
    disposeFirst() // must NOT restore the slot to null over the second guard

    await expect(checkNavigationGuard()).resolves.toBe(true)
    disposeSecond()
    await expect(checkNavigationGuard()).resolves.toBe(true)
  })

  it('treats a throwing guard as deny', async () => {
    const dispose = setNavigationGuard(() => {
      throw new Error('boom')
    })
    await expect(checkNavigationGuard()).resolves.toBe(false)
    dispose()
  })
})
