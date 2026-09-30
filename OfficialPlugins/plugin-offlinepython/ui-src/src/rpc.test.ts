import { expect, it } from 'vitest'
import { checked } from './rpc'

it('returns the result when an operation reports success', () => {
  const ok = { success: true, summary: 'ok', jobId: 'job_1' }
  expect(checked(ok)).toBe(ok)
})

it('throws the worker summary when an operation reports failure', () => {
  // Mirrors the legacy callChecked contract: a {success:false} envelope surfaces as a thrown
  // Error carrying the worker's localized summary, so panels can catch + toast it.
  expect(() => checked({ success: false, summary: 'requirements failed' })).toThrow('requirements failed')
})
