import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '@/api/client'
import type { AgentTaskCapacity, AgentTaskSummary } from '@/api/types'

/**
 * Shared view of backend-owned background work.
 *
 * The Flow builder used to own this polling state, which meant that queued or running
 * workflow tasks disappeared from the rest of the application. The shell starts this
 * store once so every surface can render the same snapshot.
 */
export const useBackgroundTasksStore = defineStore('backgroundTasks', () => {
  const tasks = ref<AgentTaskSummary[]>([])
  const capacity = ref<AgentTaskCapacity | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let timer: number | null = null
  let request: Promise<boolean> | null = null

  const activeTasks = computed(() => tasks.value.filter((task) =>
    task.status === 'queued' || task.status === 'running'))
  const runningCount = computed(() => tasks.value.filter((task) => task.status === 'running').length)
  const queuedCount = computed(() => tasks.value.filter((task) => task.status === 'queued').length)

  async function refresh(): Promise<boolean> {
    if (request) return request
    loading.value = true
    request = Promise.all([api.agentTasks(), api.agentTaskCapacity()])
      .then(([nextTasks, nextCapacity]) => {
        tasks.value = nextTasks
        capacity.value = nextCapacity
        error.value = null
        return true
      })
      .catch((e: unknown) => {
        error.value = e instanceof Error ? e.message : 'Failed to load background tasks'
        return false
      })
      .finally(() => {
        loading.value = false
        request = null
      })
    return request
  }

  function start() {
    if (timer !== null) return
    void refresh()
    timer = window.setInterval(() => void refresh(), 5_000)
  }

  function stop() {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  }

  return {
    tasks,
    capacity,
    loading,
    error,
    activeTasks,
    runningCount,
    queuedCount,
    refresh,
    start,
    stop,
  }
})
