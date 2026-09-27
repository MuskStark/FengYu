import { i18n } from '@/i18n'

export type ToolActivityStatus = 'waiting' | 'running' | 'completed' | 'failed' | 'rejected'

/** Locale-aware activity label (activities are session-scoped, so a locale switch only affects rows created after it). */
function t(key: string, params?: Record<string, unknown>): string {
  return params === undefined ? i18n.global.t(key) : i18n.global.t(key, params)
}

/** One todo step as todo_write returns it. */
export interface TodoStep {
  content: string
  status: 'pending' | 'in_progress' | 'completed'
}

export interface ToolActivity {
  id: string
  name: string
  label: string
  status: ToolActivityStatus
  detail: string
  /** Unified diff returned by write_file/edit_file (workspace coding tools); rendered on demand. */
  diff?: string
  /** Workspace-relative target path (file tools); lets the timeline offer "open in panel". */
  path?: string
  /** Full tool arguments captured at call time (bounded rendering happens in the card). */
  args: Record<string, unknown>
  /** Raw result text (bounded); the card parses per-tool views out of it. */
  output: string
  /** Human-facing error text extracted from a failed result. */
  error?: string
  startedAt: number
}

/** Tools whose result JSON may carry a `diff` field worth showing in the timeline. */
const DIFF_TOOLS = new Set(['write_file', 'edit_file'])

/** Tools whose arguments name a workspace file; recorded for the open-file gesture. */
const PATH_TOOLS = new Set(['read_file', 'write_file', 'edit_file'])

/** Result text beyond this is kept clipped in the model (cards expand from the same string). */
const MAX_OUTPUT_CHARS = 8000

export function applyToolActivity(items: ToolActivity[], payload: Record<string, unknown>): ToolActivity | null {
  const phase = text(payload.phase)
  const id = text(payload.id) || text(payload.approvalId) || `${text(payload.name)}-${items.length}`
  const name = text(payload.name)
  if (phase === 'call' || phase === 'approval_required') {
    const args = record(payload.arguments)
    let item = items.find(value => value.id === id)
    if (!item) {
      item = { id, name, label: activityLabel(name, args), status: 'running', detail: activityDetail(name, args),
        args, output: '', startedAt: Date.now() }
      if (PATH_TOOLS.has(name)) item.path = text(args.path)
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
    const output = text(payload.output)
    item.output = output.length > MAX_OUTPUT_CHARS ? output.slice(0, MAX_OUTPUT_CHARS) : output
    const diff = resultDiff(item.name, payload.output)
    if (diff !== undefined) item.diff = diff
    const error = resultError(payload.output)
    if (error && item.status === 'failed') item.error = error
    return item
  }
  return null
}

export function activityLabel(name: string, args: Record<string, unknown>): string {
  if (name === 'skill') return t('aichat.toolReadSkill', { title: skillTitle(text(args.id)) })
  if (name === 'skill_resource') return t('aichat.toolReadResource', { id: text(args.id), path: text(args.path) })
  if (name === 'execute_command' || name === 'workspace_exec') return t('aichat.toolRun', { target: firstLine(text(args.command)) || 'command' })
  if (name === 'explore') return t('aichat.toolExplore', { target: firstLine(text(args.task)) || 'workspace' })
  if (name === 'todo_write') return t('aichat.toolTodo')
  if (name === 'read_file') return t('aichat.toolReadPath', { path: text(args.path) })
  if (name === 'write_file') return t('aichat.toolWritePath', { path: text(args.path) })
  if (name === 'edit_file') return t('aichat.toolEditPath', { path: text(args.path) })
  if (name === 'grep') return t('aichat.toolSearch', { target: text(args.pattern) || 'content' })
  if (name === 'glob') return t('aichat.toolFind', { target: text(args.pattern) || 'files' })
  const subject = name.replace(/_/g, ' ').replace(/\b\w/g, value => value.toUpperCase())
  if (/(analyze|query|list|status|verify|doctor|read)/i.test(name)) return t('aichat.toolRead', { subject })
  if (/(write|save|execute|build|init|configure|cancel|send|fetch)/i.test(name)) return t('aichat.toolUpdate', { subject })
  return t('aichat.toolUse', { subject })
}

// ── per-tool result views (parsed by the cards) ───────────────────────────────

/** Parsed JSON result of a workspace/execute-family tool; null when not JSON. */
export function parseToolOutput(output: string): Record<string, unknown> | null {
  if (!output) return null
  try {
    const parsed = JSON.parse(output) as unknown
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? parsed as Record<string, unknown> : null
  } catch {
    return null
  }
}

/** The todo list of the LATEST todo_write call, or null when the output carries none. */
export function todosFromOutput(output: string): TodoStep[] | null {
  const parsed = parseToolOutput(output)
  if (!parsed || !Array.isArray(parsed.todos)) return null
  const steps = parsed.todos
    .map((item): TodoStep | null => {
      if (typeof item !== 'object' || item === null) return null
      const content = text((item as Record<string, unknown>).content)
      const status = text((item as Record<string, unknown>).status)
      if (!content) return null
      return { content, status: status === 'in_progress' || status === 'completed' ? status : 'pending' }
    })
    .filter((item): item is TodoStep => item !== null)
  return steps.length > 0 ? steps : null
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

/** Extracts the `error` field from a failed JSON result envelope. */
function resultError(output: unknown): string | undefined {
  if (typeof output !== 'string' || !output) return undefined
  try {
    const parsed = JSON.parse(output) as { error?: unknown }
    return typeof parsed.error === 'string' && parsed.error ? parsed.error : undefined
  } catch {
    return undefined
  }
}

function activityDetail(name: string, args: Record<string, unknown>): string {
  if (name === 'execute_command' || name === 'workspace_exec') return text(args.workingDirectory) || text(args.cwd)
  return ''
}

function skillTitle(id: string): string {
  if (!id) return ''
  return id.split(/[-_.]+/).map(part => part.toLowerCase() === 'fengyu'
    ? 'FengYu' : part.charAt(0).toUpperCase() + part.slice(1)).join(' ')
}

function firstLine(value: string): string {
  const newline = value.indexOf('\n')
  return newline >= 0 ? value.slice(0, newline) : value
}

function record(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown> : {}
}

function text(value: unknown): string { return typeof value === 'string' ? value : '' }
