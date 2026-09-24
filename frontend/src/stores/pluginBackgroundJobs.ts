import { create } from 'zustand'
import { services } from '@/services'

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
 * Tracks plugin-domain jobs independently from the current iframe — React port of the Vue
 * pluginBackgroundJobs store.
 *
 * A plugin's `*_start` RPC returns immediately with a jobId, while its `*_status` RPC owns
 * the actual progress. Keeping this ledger in a store lets the job survive PluginPage
 * unmounting when the user switches to another tool, and feeds the shell's background
 * execution indicator (polled every 2s while the indicator is mounted). The conventional
 * start/status naming is part of the plugin background-job contract used by the official
 * plugins.
 */
interface PluginBackgroundJobsState {
  jobs: PluginBackgroundJob[]
  add: (pluginId: string, startMethod: string, result: unknown) => void
  remove: (key: string) => void
  refresh: () => void
  start: () => void
  stop: () => void
}

let timer: number | null = null

export const usePluginBackgroundJobsStore = create<PluginBackgroundJobsState>((set, get) => ({
  jobs: [],

  add: (pluginId, startMethod, result) => {
    const job = backgroundJobFromResult(pluginId, startMethod, result)
    if (!job) return
    set((state) => {
      const existing = state.jobs.findIndex((item) => item.key === job.key)
      const next: PluginBackgroundJob = { ...job, polling: false }
      const jobs = state.jobs.slice()
      if (existing >= 0) jobs[existing] = { ...jobs[existing]!, ...next }
      else jobs.push(next)
      return { jobs }
    })
    void refreshJob(get().jobs.find((item) => item.key === job.key)!)
  },

  remove: (key) => set((state) => ({ jobs: state.jobs.filter((job) => job.key !== key) })),

  refresh: () => {
    for (const job of get().jobs.slice()) void refreshJob(job)
  },

  start: () => {
    if (timer !== null) return
    timer = window.setInterval(() => get().refresh(), 2_000)
  },

  stop: () => {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  },
}))

/** One job's *_status probe; updates the ledger in place (or drops it when terminal). */
async function refreshJob(job: PluginBackgroundJob): Promise<void> {
  const current = usePluginBackgroundJobsStore.getState().jobs.find((item) => item.key === job.key)
  if (!current || current.polling) return
  patchJob(current.key, { polling: true })
  try {
    const result = record(await services.plugin.invokeMethod(
      current.pluginId,
      current.statusMethod,
      { jobId: current.jobId },
    ))
    if (result.success === false || result.done === true || isTerminalStatus(result.status)) {
      usePluginBackgroundJobsStore.getState().remove(current.key)
      return
    }
    const status = typeof result.status === 'string' ? result.status.toUpperCase() : ''
    patchJob(current.key, {
      status: status === 'QUEUED' || status === 'STARTING' ? 'queued' : 'running',
      lastError: null,
    })
  } catch (e) {
    // Keep the ledger alive across a transient backend/worker failure. The next poll can
    // recover, and the indicator remains honest that the job is no longer observable.
    patchJob(current.key, {
      status: 'unknown',
      lastError: e instanceof Error ? e.message : 'Failed to read plugin job status',
    })
  } finally {
    patchJob(current.key, { polling: false })
  }
}

function patchJob(key: string, patch: Partial<PluginBackgroundJob>): void {
  usePluginBackgroundJobsStore.setState((state) => ({
    jobs: state.jobs.map((job) => (job.key === key ? { ...job, ...patch } : job)),
  }))
}
