export type ToolActivityStatus = 'waiting' | 'running' | 'completed' | 'failed' | 'rejected'

export interface ToolActivity {
  id: string
  name: string
  label: string
  status: ToolActivityStatus
  detail: string
}

export function applyToolActivity(items: ToolActivity[], payload: Record<string, unknown>): ToolActivity | null {
  const phase = text(payload.phase)
  const id = text(payload.id) || text(payload.approvalId) || `${text(payload.name)}-${items.length}`
  const name = text(payload.name)
  if (phase === 'call' || phase === 'approval_required') {
    const args = record(payload.arguments)
    let item = items.find(value => value.id === id)
    if (!item) {
      item = { id, name, label: activityLabel(name, args), status: 'running', detail: activityDetail(name, args) }
      items.push(item)
    }
    item.status = phase === 'approval_required' ? 'waiting' : 'running'
    return item
  }
  if (phase === 'result') {
    const item = items.find(value => value.id === id)
    if (!item) return null
    item.status = payload.success === false ? 'failed' : 'completed'
    return item
  }
  return null
}

export function activityLabel(name: string, args: Record<string, unknown>): string {
  if (name === 'skill') return `Read ${skillTitle(text(args.id))} skill`
  if (name === 'skill_resource') return `Read ${text(args.id)}/${text(args.path)}`
  if (name === 'execute_command') return `Run ${text(args.command) || 'command'}`
  const subject = name.replace(/_/g, ' ').replace(/\b\w/g, value => value.toUpperCase())
  if (/(analyze|query|list|status|verify|doctor|read)/i.test(name)) return `Read ${subject}`
  if (/(write|save|execute|build|init|configure|cancel|send|fetch)/i.test(name)) return `Update ${subject}`
  return `Use ${subject}`
}

function activityDetail(name: string, args: Record<string, unknown>): string {
  if (name === 'execute_command') return text(args.workingDirectory)
  return ''
}

function skillTitle(id: string): string {
  if (!id) return ''
  return id.split(/[-_.]+/).map(part => part.toLowerCase() === 'fengyu'
    ? 'FengYu' : part.charAt(0).toUpperCase() + part.slice(1)).join(' ')
}

function record(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown> : {}
}

function text(value: unknown): string { return typeof value === 'string' ? value : '' }
