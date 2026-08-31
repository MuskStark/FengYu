import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '@/api/client'

export type PluginBackgroundJobStatus = 'queued' | 'running' | 'unknown'

export interface PluginBackgroundJob {
  key: string
  pluginId: string
  startMethod: string
  statusMethod: string
  jobId: string
  status: PluginBackgroundJobStatus
  lastError: string | null
  polling: boolean
}

/** Return a plugin-domain background job from a start RPC result, if one was launched. */
export function backgroundJobFromResult(
  pluginId: string,
  startMethod: string,
  result: unknown,
): Omit<PluginBackgroundJob, 'polling'> | null {
  if (!startMethod.endsWith('_start') || typeof result !== 'object' || result === null) return null
  const body = result as Record<string, unknown>
  if (body.success === false) return null
  const jobId = typeof body.jobId === 'string' ? body.jobId.trim() : ''
  if (!jobId) return null
  return {
    key: `${pluginId}:${jobId}`,
    pluginId,
    startMethod,
    statusMethod: `${startMethod.slice(0, -'_start'.length)}_status`,
    jobId,
    status: 'running',
    lastError: null,
  }
}

function record(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : {}
}

function isTerminalStatus(status: unknown): boolean {
  return typeof status === 'string'
    && ['DONE', 'COMPLETED', 'FAILED', 'CANCELLED', 'ERROR'].includes(status.toUpperCase())
}

/**
 * Tracks plugin-domain jobs independently from the current iframe.
 *
 * A plugin's `*_start` RPC returns immediately with a jobId, while its `*_status` RPC owns the
 * actual progress. Keeping this ledger in Pinia lets the job survive PluginView unmounting when
 * the user switches to another tool. The conventional start/status naming is part of the plugin
 * background-job contract used by the official plugins.
 */
export const usePluginBackgroundJobsStore = defineStore('pluginBackgroundJobs', () => {
  const jobs = ref<PluginBackgroundJob[]>([])
  let timer: number | null = null
  let sequence = 0

  const activeJobs = computed(() => jobs.value)
  const runningCount = computed(() => jobs.value.filter((job) => job.status === 'running').length)
  const queuedCount = computed(() => jobs.value.filter((job) => job.status === 'queued').length)
  const unknownCount = computed(() => jobs.value.filter((job) => job.status === 'unknown').length)
  const latestJob = computed(() => jobs.value.at(-1) ?? null)

  function add(pluginId: string, startMethod: string, result: unknown) {
    const job = backgroundJobFromResult(pluginId, startMethod, result)
    if (!job) return
    const existing = jobs.value.findIndex((item) => item.key === job.key)
    const next: PluginBackgroundJob = { ...job, polling: false }
    if (existing >= 0) jobs.value[existing] = { ...jobs.value[existing], ...next }
    else jobs.value.push(next)
    void refreshJob(next)
  }

  function remove(key: string) {
    jobs.value = jobs.value.filter((job) => job.key !== key)
  }

  async function refreshJob(job: PluginBackgroundJob): Promise<void> {
    const current = jobs.value.find((item) => item.key === job.key)
    if (!current || current.polling) return
    current.polling = true
    try {
      const result = record(await api.pluginInvoke(
        current.pluginId,
        current.statusMethod,
        { jobId: current.jobId },
        { callId: `ui_bg_${Date.now()}_${++sequence}` },
      ))
      if (result.success === false || result.done === true || isTerminalStatus(result.status)) {
        remove(current.key)
        return
      }
      const status = typeof result.status === 'string' ? result.status.toUpperCase() : ''
      current.status = status === 'QUEUED' || status === 'STARTING' ? 'queued' : 'running'
      current.lastError = null
    } catch (e) {
      // Keep the ledger alive across a transient backend/worker failure. The next poll can
      // recover, and the indicator remains honest that the job is no longer observable.
      current.status = 'unknown'
      current.lastError = e instanceof Error ? e.message : 'Failed to read plugin job status'
    } finally {
      current.polling = false
    }
  }

  function refresh() {
    for (const job of jobs.value.slice()) void refreshJob(job)
  }

  function start() {
    if (timer !== null) return
    timer = window.setInterval(refresh, 2_000)
  }

  function stop() {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  }

  return {
    jobs,
    activeJobs,
    runningCount,
    queuedCount,
    unknownCount,
    latestJob,
    add,
    remove,
    refresh,
    start,
    stop,
  }
})
