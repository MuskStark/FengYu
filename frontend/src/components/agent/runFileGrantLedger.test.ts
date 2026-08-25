import { describe, expect, it } from 'vitest'
import type { ActiveFileEntry } from '@/api/types'
import { createRunFileGrantLedger } from '@/components/agent/runFileGrantLedger'

let refCounter = 0
function entry(pluginId = 'fan.summer.excel'): ActiveFileEntry {
  refCounter += 1
  return { pluginId, ref: { id: `ref_${refCounter}`, name: `f${refCounter}`, kind: 'file', access: 'read', size: 1 } }
}

function ledger() {
  const revoked: string[] = []
  const handle = createRunFileGrantLedger((item) => revoked.push(item.ref.id))
  return { handle, revoked }
}

describe('runFileGrantLedger', () => {
  it('revokes replaced and cleared grants while the dialog owns them', () => {
    const { handle, revoked } = ledger()
    const session = handle.beginSession()

    const first = [entry(), entry()]
    expect(handle.accept(session, 'src', first)).toBe(true)
    expect(handle.accept(session, 'src', [entry()])).toBe(true)
    // replacing an input revokes the old grants
    expect(revoked).toEqual([first[0].ref.id, first[1].ref.id])

    const kept = [entry()]
    handle.accept(session, 'out', kept)
    handle.clear('out')
    // clearing an input revokes its grants
    expect(revoked).toContain(kept[0].ref.id)
  })

  it('releases every remaining grant on close, but nothing after ownership transferred', () => {
    const { handle, revoked } = ledger()
    const session = handle.beginSession()
    handle.accept(session, 'src', [entry()])
    handle.releaseRemaining()
    expect(revoked).toHaveLength(1)

    const next = handle.beginSession()
    const consumed = [entry()]
    handle.accept(next, 'src', consumed)
    handle.markTransferred()
    handle.releaseRemaining()
    handle.clear('src')
    // a run now owns the grants; the dialog must not revoke them
    expect(revoked).toHaveLength(1)
  })

  it('revokes late picker grants that outlive their dialog session', () => {
    const { handle, revoked } = ledger()
    const staleSession = handle.beginSession()
    const nextSession = handle.beginSession()

    // Request from the previous opening resolves after reopen…
    const late = [entry()]
    expect(handle.accept(staleSession, 'src', late)).toBe(false)
    // stale grants are handed straight back
    expect(revoked).toEqual([late[0].ref.id])

    // …and anything resolving after submit is equally unowned by the dialog.
    const postSubmit = [entry()]
    handle.accept(nextSession, 'src', postSubmit)
    handle.markTransferred()
    expect(handle.accept(nextSession, 'other', [entry()])).toBe(false)
    // adopted-then-transferred grants belong to the run, never the dialog
    expect(revoked).not.toContain(postSubmit[0].ref.id)
  })
})
