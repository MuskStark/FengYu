/**
 * Single-slot navigation guard for unsaved-work protection. The surface holding
 * unsaved state (the flow builder) registers a confirm callback while dirty;
 * every shell navigation entry point consults `checkNavigationGuard()` before
 * calling navigate(). This replaces the old `history.pushState` monkey-patch,
 * whose synchronous window.confirm could not actually cancel a React Router
 * transition (the router swaps the view even when the pushState is swallowed).
 */

type NavigationGuard = () => Promise<boolean> | boolean

let guard: NavigationGuard | null = null

/** Install `fn` as the active guard; the disposer clears the slot only if `fn` still owns it. */
export function setNavigationGuard(fn: NavigationGuard | null): () => void {
  guard = fn
  return () => {
    if (guard === fn) guard = null
  }
}

/** True when no guard is registered or the guard allows the navigation. */
export async function checkNavigationGuard(): Promise<boolean> {
  if (!guard) return true
  try {
    return await guard()
  } catch {
    return false
  }
}
