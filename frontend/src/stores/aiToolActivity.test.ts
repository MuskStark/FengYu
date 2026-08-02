import { describe, expect, it } from 'vitest'
import { applyToolActivity, type ToolActivity } from './aiToolActivity'

describe('AI tool activity timeline', () => {
  it('renders skill loading like Codex and completes the same row', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'call', id: 'c1', name: 'skill', arguments: { id: 'fengyu-plugin-dev' } })
    expect(items[0]).toMatchObject({ label: 'Read FengYu Plugin Dev skill', status: 'running' })
    applyToolActivity(items, { phase: 'result', id: 'c1', success: true })
    expect(items[0].status).toBe('completed')
  })

  it('shows command approvals as a waiting activity', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'approval_required', id: 'c2', approvalId: 'a1',
      name: 'execute_command', arguments: { command: './mvnw test' } })
    expect(items[0]).toMatchObject({ label: 'Run ./mvnw test', status: 'waiting' })
  })
})
