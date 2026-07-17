import { expect, it, vi } from 'vitest'
import type { FengYuClient } from '@infinia/plugin-sdk'
import { callChecked } from './rpc'

it('throws the worker summary when an operation reports failure', async () => {
  const client = { invoke: vi.fn().mockResolvedValue({ success: false, summary: 'requirements failed' }) } as unknown as FengYuClient
  await expect(callChecked(client, 'requirements.save', {})).rejects.toThrow('requirements failed')
})
