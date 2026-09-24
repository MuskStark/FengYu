import { create } from 'zustand'

/**
 * In-shell toasts (bottom-right, auto-dismiss, click-through links) — the React port of the
 * Vue NotificationToasts surface. Backend notification events push through `push` carrying
 * their `notificationId` so read/delete actions can retire the matching live toast; the
 * list is capped and transient by design (Vue: max 3, 6s TTL).
 */
export interface ToastMessage {
  id: number
  level: 'info' | 'success' | 'warning' | 'error'
  title: string
  body?: string
  link?: { label: string; to: string }
  /** Backend notification this toast surfaces (marks it read on click). */
  notificationId?: number
}

interface ToastStore {
  toasts: ToastMessage[]
  push: (toast: Omit<ToastMessage, 'id'>) => void
  dismiss: (id: number) => void
  /** Retire every live toast backed by one backend notification (read/deleted). */
  dismissForNotification: (notificationId: number) => void
}

const TOAST_TTL_MS = 6_000
const MAX_TOASTS = 3
const toastTimers = new Map<number, number>()
let nextToastId = 1

function scheduleDismiss(id: number): void {
  toastTimers.set(id, window.setTimeout(() => {
    toastTimers.delete(id)
    useToastStore.setState((state) => ({ toasts: state.toasts.filter(item => item.id !== id) }))
  }, TOAST_TTL_MS))
}

function clearTimer(id: number): void {
  const timer = toastTimers.get(id)
  if (timer !== undefined) {
    window.clearTimeout(timer)
    toastTimers.delete(id)
  }
}

export const useToastStore = create<ToastStore>((set) => ({
  toasts: [],
  push: (toast) => {
    const id = nextToastId++
    scheduleDismiss(id)
    set((state) => {
      const toasts = [...state.toasts, { ...toast, id }]
      while (toasts.length > MAX_TOASTS) {
        const oldest = toasts.shift()!
        clearTimer(oldest.id)
      }
      return { toasts }
    })
  },
  dismiss: (id) => {
    clearTimer(id)
    set((state) => ({ toasts: state.toasts.filter(item => item.id !== id) }))
  },
  dismissForNotification: (notificationId) => set((state) => ({
    toasts: state.toasts.filter((item) => {
      if (item.notificationId !== notificationId) return true
      clearTimer(item.id)
      return false
    }),
  })),
}))

/** Global error surface: the equivalent of the Vue main.ts errorHandler → toast pipeline. */
export function toastError(message: string) {
  useToastStore.getState().push({ level: 'error', title: message })
}
