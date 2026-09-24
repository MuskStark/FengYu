import { useCallback } from 'react'
import { useNavigate, type NavigateOptions, type To } from 'react-router-dom'
import { checkNavigationGuard } from '@/lib/navGuard'

/**
 * navigate() wrapper that consults the unsaved-work navigation guard first —
 * the guard shows the in-app discard dialog and the navigation only proceeds
 * on confirm. Use for shell nav surfaces (sidebar, shortcuts, notifications).
 */
export default function useGuardedNavigate() {
  const navigate = useNavigate()
  return useCallback((to: To, options?: NavigateOptions) => {
    void (async () => {
      if (await checkNavigationGuard()) navigate(to, options)
    })()
  }, [navigate])
}
