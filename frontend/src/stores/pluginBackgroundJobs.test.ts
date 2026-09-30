import { describe, expect, it } from 'vitest'
import { backgroundJobFromResult } from './pluginBackgroundJobs'

describe('plugin background job registration', () => {
  it('derives the status method from a start method and keeps the job id', () => {
    expect(backgroundJobFromResult('fan.summer.excel', 'split_start', {
      jobId: 'job-1', success: true,
    })).toMatchObject({
      pluginId: 'fan.summer.excel',
      jobId: 'job-1',
      statusMethod: 'split_status',
      status: 'running',
    })
  })

  it('does not register synchronous or unsuccessful responses without a job id', () => {
    expect(backgroundJobFromResult('plugin', 'split', { success: true })).toBeNull()
    expect(backgroundJobFromResult('plugin', 'split_start', { success: false })).toBeNull()
    expect(backgroundJobFromResult('plugin', 'split_start', { success: true, jobId: '  ' })).toBeNull()
  })
})
