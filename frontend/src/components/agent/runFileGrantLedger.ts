import type { ActiveFileEntry } from '@/api/types'

/**
 * Ownership ledger for the grants a run dialog's file pickers mint.
 *
 * A grant has exactly one owner at any time: the dialog owns it from minting until either the
 * user discards it (replace / clear / close / unmount — revoked here) or a run is created from
 * it (`markTransferred` — the run's terminal cleanup now owns the revoke, so the dialog must
 * never touch it again). Picker requests that resolve after the dialog closed or reopened hand
 * their grants straight back instead of attaching them to dead form state.
 */
export interface RunFileGrantLedger {
  /** Starts a fresh dialog session; returns its id for async picker completions. */
  beginSession(): number
  /** Current session id (capture before an await). */
  session(): number
  /**
   * Make `entries` the current grants of input `name`, revoking what they replace. Returns false
   * (and revokes `entries`) when the completing request belongs to an earlier session or the
   * dialog already handed ownership to a run — the caller must then leave its state untouched.
   */
  accept(sessionAtStart: number, name: string, entries: ActiveFileEntry[]): boolean
  /** The user cleared one input: its grants are still dialog-owned, so revoke them. */
  clear(name: string): void
  /** Close/unmount without submitting: every remaining grant is still dialog-owned. */
  releaseRemaining(): void
  /** A run consumed the grants (or is about to); ownership left the dialog. */
  markTransferred(): void
}

export function createRunFileGrantLedger(
  revoke: (entry: ActiveFileEntry) => void,
): RunFileGrantLedger {
  let currentSession = 0
  let transferred = false
  const byInput = new Map<string, ActiveFileEntry[]>()

  function revokeAll(entries: ActiveFileEntry[]): void {
    for (const entry of entries) revoke(entry)
  }

  function releaseRemaining(): void {
    if (transferred) return
    for (const entries of byInput.values()) revokeAll(entries)
    byInput.clear()
  }

  return {
    beginSession() {
      // Whatever a previous session still owned was released on close; a leftover here (close
      // never observed) must not survive into the fresh session silently.
      releaseRemaining()
      transferred = false
      currentSession += 1
      return currentSession
    },
    session() {
      return currentSession
    },
    accept(sessionAtStart, name, entries) {
      if (sessionAtStart !== currentSession || transferred) {
        revokeAll(entries)
        return false
      }
      revokeAll(byInput.get(name) ?? [])
      byInput.set(name, entries)
      return true
    },
    clear(name) {
      if (transferred) return
      revokeAll(byInput.get(name) ?? [])
      byInput.delete(name)
    },
    releaseRemaining,
    markTransferred() {
      transferred = true
      byInput.clear()
    },
  }
}
