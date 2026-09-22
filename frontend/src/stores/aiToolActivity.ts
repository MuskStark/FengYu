export type ToolActivityStatus = 'waiting' | 'running' | 'completed' | 'failed' | 'rejected'

export interface ToolActivity {
  id: string
  name: string
  label: string
  status: ToolActivityStatus
  detail: string
  /** Unified diff returned by write_file/edit_file (workspace coding tools); rendered on demand. */
  diff?: string
}

/** Tools whose result JSON may carry a `diff` field worth showing in the timeline. */
const DIFF_TOOLS = new Set(['write_file', 'edit_file'])

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
    // Result events carry no tool name — the call phase recorded it on the row.
    const diff = resultDiff(item.name, payload.output)
    if (diff !== undefined) item.diff = diff
    return item
  }
  return null
}

export function activityLabel(name: string, args: Record<string, unknown>): string {
  if (name === 'skill') return `Read ${skillTitle(text(args.id))} skill`
  if (name === 'skill_resource') return `Read ${text(args.id)}/${text(args.path)}`
  if (name === 'execute_command') return `Run ${text(args.command) || 'command'}`
  if (name === 'read_file') return `Read ${text(args.path)}`
  if (name === 'write_file') return `Write ${text(args.path)}`
  if (name === 'edit_file') return `Edit ${text(args.path)}`
  if (name === 'grep') return `Search ${text(args.pattern) || 'content'}`
  if (name === 'glob') return `Find ${text(args.pattern) || 'files'}`
  const subject = name.replace(/_/g, ' ').replace(/\b\w/g, value => value.toUpperCase())
  if (/(analyze|query|list|status|verify|doctor|read)/i.test(name)) return `Read ${subject}`
  if (/(write|save|execute|build|init|configure|cancel|send|fetch)/i.test(name)) return `Update ${subject}`
  return `Use ${subject}`
}

// ── diff rendering support ────────────────────────────────────────────────────

export type DiffLineKind = 'add' | 'del' | 'hunk' | 'ctx'

export interface DiffLine {
  kind: DiffLineKind
  text: string
}

/** Splits a unified diff into per-line render rows; file headers render as context. */
export function diffLines(diff: string): DiffLine[] {
  return diff.split('\n')
    .filter(line => line !== '')
    .map(line => ({
      kind: line.startsWith('@@') ? 'hunk'
        : line.startsWith('+') && !line.startsWith('+++') ? 'add'
          : line.startsWith('-') && !line.startsWith('---') ? 'del'
            : 'ctx' as DiffLineKind,
      text: line,
    }))
}

/** Extracts the `diff` field from a workspace tool's JSON result; undefined when absent. */
function resultDiff(name: string, output: unknown): string | undefined {
  if (!DIFF_TOOLS.has(name) || typeof output !== 'string' || !output) return undefined
  try {
    const parsed = JSON.parse(output) as { diff?: unknown }
    return typeof parsed.diff === 'string' && parsed.diff ? parsed.diff : undefined
  } catch {
    return undefined
  }
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
