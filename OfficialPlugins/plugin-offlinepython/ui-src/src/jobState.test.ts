import { describe, expect, it } from 'vitest'
import { readJobSnapshot, statusKey } from './jobState'

describe('job state', () => {
  it.each([
    ['RUNNING', 'running'],
    ['DONE', 'done'],
    ['FAILED', 'failed'],
    ['CANCELLED', 'cancelled'],
  ])('normalizes worker status %s', (worker, ui) => {
    expect(statusKey(worker)).toBe(ui)
  })

  it('returns logs and completion from a successful snapshot', () => {
    expect(readJobSnapshot({
      success: true,
      summary: 'job status',
      status: 'DONE',
      logs: ['complete'],
      done: true,
    })).toEqual({ ok: true, summary: 'job status', status: 'done', logs: ['complete'], done: true })
  })

  it('turns a failed RPC envelope into a terminal UI error', () => {
    expect(readJobSnapshot({ success: false, summary: 'unknown job' })).toEqual({
      ok: false, summary: 'unknown job', status: 'error', logs: [], done: true,
    })
  })
})
