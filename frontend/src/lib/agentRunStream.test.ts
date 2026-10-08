import { describe, expect, it, vi } from 'vitest'
import { swapStreamHandle } from './agentRunStream'
import type { StreamHandle } from '@/services/agent'

describe('swapStreamHandle (no orphaned EventSource across handle transitions)', () => {
  it('closes the live predecessor before installing the next handle', () => {
    const first: StreamHandle = { close: vi.fn() }
    const second: StreamHandle = { close: vi.fn() }
    const ref = { current: first as StreamHandle | null }

    swapStreamHandle(ref, second)
    expect(first.close).toHaveBeenCalledOnce()
    expect(second.close).not.toHaveBeenCalled()
    expect(ref.current).toBe(second)
  })

  it('tolerates a null predecessor (first attach) and a null successor (close)', () => {
    const ref: { current: StreamHandle | null } = { current: null }
    swapStreamHandle(ref, null)
    expect(ref.current).toBeNull()

    const handle: StreamHandle = { close: vi.fn() }
    ref.current = handle
    swapStreamHandle(ref, null)
    expect(handle.close).toHaveBeenCalledOnce()
    expect(ref.current).toBeNull()
  })
})
