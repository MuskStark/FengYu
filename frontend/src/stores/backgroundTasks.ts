import { create } from 'zustand'
import { services } from '@/services'
import type { AgentTaskCapacity, AgentTaskSummary } from '@/services/types'

/**
 * Shared view of backend-owned background work — React port of the Vue backgroundTasks
 * store. The shell starts the poll once so every surface (the flow builder, the background
 * execution indicator) renders the same snapshot; queued or running workflow tasks never
 * disappear just because the builder closed.
 */
interface BackgroundTasksState {
  tasks: AgentTaskSummary[]
  capacity: AgentTaskCapacity | null
  error: string | null
  refresh: () => Promise<boolean>
  start: () => void
  stop: () => void
}

let timer: number | null = null
let inFlight: Promise<boolean> | null = null

export const useBackgroundTasksStore = create<BackgroundTasksState>((set, get) => ({
  tasks: [],
  capacity: null,
  error: null,

  refresh: () => {
    if (inFlight) return inFlight
    inFlight = Promise.all([services.agent.tasks(), services.agent.taskCapacity()])
      .then(([tasks, capacity]) => {
        set({ tasks, capacity, error: null })
        return true
      })
      .catch((e: unknown) => {
        set({ error: e instanceof Error ? e.message : 'Failed to load background tasks' })
        return false
      })
      .finally(() => { inFlight = null })
    return inFlight
  },

  start: () => {
    if (timer !== null) return
    void get().refresh()
    timer = window.setInterval(() => void get().refresh(), 5_000)
  },

  stop: () => {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  },
}))
