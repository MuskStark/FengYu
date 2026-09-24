import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  appConfirm,
  appPrompt,
  currentDialog,
  finishDialog,
  subscribeDialogs,
} from './appDialogs'

async function tick() {
  await Promise.resolve()
  await Promise.resolve()
}

// Drain any dialog the test left open so the next one starts from a clean store.
afterEach(async () => {
  let guard = 0
  while (currentDialog() && guard++ < 10) {
    finishDialog(currentDialog()!.id, false)
    await tick()
  }
})

describe('appDialogs store', () => {
  it('appConfirm renders a dialog the host can confirm', async () => {
    const listener = vi.fn()
    const unsubscribe = subscribeDialogs(listener)

    const promise = appConfirm('Discard changes?')
    await tick()

    const dialog = currentDialog()
    expect(dialog).not.toBeNull()
    expect(dialog!.kind).toBe('confirm')
    expect(dialog!.message).toBe('Discard changes?')
    finishDialog(dialog!.id, true)

    await expect(promise).resolves.toBe(true)
    expect(currentDialog()).toBeNull()
    unsubscribe()
  })

  it('appPrompt resolves null on cancel and the typed string on confirm', async () => {
    const cancelled = appPrompt('Workspace folder', { initial: '/tmp' })
    await tick()
    expect(currentDialog()!.kind).toBe('prompt')
    expect(currentDialog()!.initial).toBe('/tmp')
    finishDialog(currentDialog()!.id, null)
    await expect(cancelled).resolves.toBeNull()

    const submitted = appPrompt('Workspace folder')
    await tick()
    finishDialog(currentDialog()!.id, '/home/u/project')
    await expect(submitted).resolves.toBe('/home/u/project')
  })

  it('queues a second dialog until the active one resolves (no double render)', async () => {
    const first = appConfirm('first')
    const second = appConfirm('second')
    await tick()

    expect(currentDialog()!.message).toBe('first')
    finishDialog(currentDialog()!.id, false)
    await expect(first).resolves.toBe(false)

    await tick()
    expect(currentDialog()!.message).toBe('second')
    finishDialog(currentDialog()!.id, true)
    await expect(second).resolves.toBe(true)
  })

  it('ignores stale finishDialog ids so a late host cannot resolve a newer dialog', async () => {
    const promise = appConfirm('live one')
    await tick()
    const staleId = currentDialog()!.id - 1
    finishDialog(staleId, true)
    expect(currentDialog()?.message).toBe('live one')

    finishDialog(currentDialog()!.id, true)
    await expect(promise).resolves.toBe(true)
  })

  it('notifies subscribers on subscribe, open, close, and pump', async () => {
    const seen: (string | null)[] = []
    const unsubscribe = subscribeDialogs((dialog) => seen.push(dialog?.message ?? null))
    // subscribeDialogs immediately syncs the current (empty) state, like the host mount.

    const first = appConfirm('first')
    const second = appConfirm('second')
    await tick()
    finishDialog(currentDialog()!.id, false)
    await expect(first).resolves.toBe(false)
    await tick()
    finishDialog(currentDialog()!.id, false)
    await expect(second).resolves.toBe(false)

    expect(seen).toEqual([null, 'first', 'second', null])
    unsubscribe()
  })
})
