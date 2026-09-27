import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Check, ChevronRight, CircleDashed, Copy, ExternalLink, ListChecks,
  Loader2, Search, Shield, X,
} from 'lucide-react'
import {
  diffLines, parseToolOutput, todosFromOutput,
  type ToolActivity, type TodoStep,
} from '@/lib/toolActivity'
import { cn } from '@/lib/utils'

/**
 * Tool-call card system (renderer-registry pattern): one generic
 * collapsible row — status icon, localized label, status word, chevron — whose body is
 * rendered by a per-tool renderer resolved from the activity name (fallback: args/output
 * viewers). Open/closed state lives in a module-level map so remounts (streaming
 * re-renders) keep the user's choices; a fresh diff auto-opens once, and a card that
 * finishes auto-collapses unless the user interacted with it.
 */

const openStates = new Map<string, boolean>()
const interacted = new Set<string>()
const STATE_MAP_LIMIT = 500

/** Both maps live for the whole session; oldest entries evict so they never grow unbounded. */
function rememberCardState(id: string): void {
  if (interacted.has(id)) return
  interacted.add(id)
  if (interacted.size > STATE_MAP_LIMIT) {
    const oldest = interacted.values().next().value
    if (oldest !== undefined) interacted.delete(oldest)
  }
}

function ToolStatusIcon({ status }: { status: ToolActivity['status'] }) {
  if (status === 'completed') return <Check size={14} />
  if (status === 'failed' || status === 'rejected') return <X size={14} />
  if (status === 'waiting') return <Shield size={14} />
  return <Loader2 size={14} className="cx-spin-icon" />
}

export default function ToolCard({ activity, onOpenWorkspaceFile }: {
  activity: ToolActivity
  /** Open a workspace file in the side panel. */
  onOpenWorkspaceFile: (path: string) => void
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(() => openStates.get(activity.id)
    ?? (activity.diff ? true : false))
  const [copied, setCopied] = useState(false)
  const prevStatus = useRef(activity.status)
  const autoOpened = useRef(Boolean(activity.diff))

  // Auto-open once when a diff lands; auto-collapse when a still-open card completes
  // (unless the user touched it — the user-wins rule).
  useEffect(() => {
    if (activity.diff && !autoOpened.current) {
      autoOpened.current = true
      if (!interacted.has(activity.id)) {
        openStates.set(activity.id, true)
        setOpen(true)
      }
    }
    if (prevStatus.current === 'running' && activity.status === 'completed'
        && !interacted.has(activity.id)) {
      openStates.set(activity.id, false)
      setOpen(false)
    }
    if (openStates.size > STATE_MAP_LIMIT) {
      const oldest = openStates.keys().next().value
      if (oldest !== undefined && oldest !== activity.id) openStates.delete(oldest)
    }
    prevStatus.current = activity.status
  }, [activity.id, activity.status, activity.diff])

  function toggle(): void {
    rememberCardState(activity.id)
    const next = !open
    openStates.set(activity.id, next)
    if (openStates.size > STATE_MAP_LIMIT) {
      const oldest = openStates.keys().next().value
      if (oldest !== undefined && oldest !== activity.id) openStates.delete(oldest)
    }
    setOpen(next)
  }

  async function copyError(): Promise<void> {
    try {
      await navigator.clipboard.writeText(activity.error ?? activity.output)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable */
    }
  }

  const statusWord = activity.status === 'waiting' ? t('aichat.toolStatusWaiting')
    : activity.status === 'running' ? t('aichat.toolStatusRunning')
    : activity.status === 'completed' ? t('aichat.toolStatusDone')
    : activity.status === 'rejected' ? t('aichat.toolStatusRejected')
    : t('aichat.toolStatusFailed')

  return (
    <div className={cn('chat-toolcard', activity.status === 'failed' && 'chat-toolcard--failed')}>
      <button className="chat-toolcard__head" onClick={toggle} aria-expanded={open}>
        <span className={cn('chat-toolcard__icon', activity.status === 'running' && 'chat-toolcard__icon--running')}>
          <ToolStatusIcon status={activity.status} />
        </span>
        <span className="chat-toolcard__label">{activity.label}</span>
        {activity.detail && <span className="cx-muted chat-toolcard__detail">{activity.detail}</span>}
        <span className={cn('chat-toolcard__status',
          (activity.status === 'failed' || activity.status === 'rejected') && 'chat-toolcard__status--error',
          activity.status === 'running' && 'chat-toolcard__status--running')}>
          {statusWord}
        </span>
        <ChevronRight size={14} className={cn('chat-toolcard__chevron', open && 'chat-toolcard__chevron--open')} />
      </button>
      {open && (
        <div className="chat-toolcard__body">
          {(activity.status === 'failed' || activity.status === 'rejected') && (
            <div className="chat-toolcard__error">
              <div className="chat-toolcard__error-text">
                {activity.error ?? t('aichat.toolFailedHint')}
              </div>
              <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => void copyError()}>
                <Copy size={12} />
                {copied ? t('aichat.copied') : t('aichat.copyError')}
              </button>
            </div>
          )}
          <ToolCardBody activity={activity} onOpenWorkspaceFile={onOpenWorkspaceFile} />
        </div>
      )}
    </div>
  )
}

// ── per-tool renderers ─────────────────────────────────────────────────────────

function ToolCardBody({ activity, onOpenWorkspaceFile }: {
  activity: ToolActivity
  onOpenWorkspaceFile: (path: string) => void
}) {
  switch (activity.name) {
    case 'read_file':
    case 'write_file':
    case 'edit_file':
      return <FileToolBody activity={activity} onOpenWorkspaceFile={onOpenWorkspaceFile} />
    case 'grep':
      return <GrepBody activity={activity} />
    case 'glob':
      return <GlobBody activity={activity} />
    case 'execute_command':
    case 'workspace_exec':
      return <ExecBody activity={activity} />
    case 'todo_write':
      return <TodoBody activity={activity} />
    case 'explore':
      return <ExploreBody activity={activity} />
    case 'skill':
    case 'skill_resource':
      return <PlainOutputBody activity={activity} />
    default:
      return <FallbackBody activity={activity} onOpenWorkspaceFile={onOpenWorkspaceFile} />
  }
}

function pathOf(activity: ToolActivity): string {
  return activity.path ?? String(activity.args.path ?? '')
}

function PathChip({ path, onOpenWorkspaceFile }: {
  path: string
  onOpenWorkspaceFile: (path: string) => void
}) {
  if (!path) return null
  return (
    <span className="chat-toolcard__pathrow">
      <code className="chat-toolcard__path">{path}</code>
      <button
        className="cx-btn cx-btn--text cx-btn--sm"
        onClick={() => onOpenWorkspaceFile(path)}
        title={undefined}
      >
        <ExternalLink size={12} />
      </button>
    </span>
  )
}

function FileToolBody({ activity, onOpenWorkspaceFile }: {
  activity: ToolActivity
  onOpenWorkspaceFile: (path: string) => void
}) {
  const { t } = useTranslation()
  const parsed = parseToolOutput(activity.output)
  const meta: string[] = []
  if (parsed) {
    if (typeof parsed.totalLines === 'number') meta.push(`${parsed.totalLines} ${t('aichat.toolLines')}`)
    if (typeof parsed.lines === 'number' && activity.name === 'read_file') {
      meta.push(`${t('aichat.toolFrom')} ${String(parsed.offset ?? '')} · ${parsed.lines} ${t('aichat.toolLines')}`)
    }
    if (parsed.created === true) meta.push(t('aichat.toolCreated'))
    if (typeof parsed.strategy === 'string') meta.push(parsed.strategy)
    if (typeof parsed.replacements === 'number') meta.push(`×${parsed.replacements}`)
  }
  return (
    <>
      <PathChip path={pathOf(activity)} onOpenWorkspaceFile={onOpenWorkspaceFile} />
      {meta.length > 0 && <div className="cx-muted chat-toolcard__meta">{meta.join(' · ')}</div>}
      {activity.diff ? (
        <div className="cx-diff">
          {diffLines(activity.diff).map((line, index) => (
            <div key={index} className={`cx-diff__${line.kind}`}>{line.text}</div>
          ))}
        </div>
      ) : (
        <PreviewOutput output={activity.output} />
      )}
    </>
  )
}

function GrepBody({ activity }: { activity: ToolActivity }) {
  const { t } = useTranslation()
  const parsed = parseToolOutput(activity.output)
  const matches = Array.isArray(parsed?.matches) ? parsed!.matches as Array<Record<string, unknown>> : []
  const count = typeof parsed?.count === 'number' ? parsed.count : null
  return (
    <>
      <div className="cx-muted chat-toolcard__meta">
        <Search size={12} />
        {String(activity.args.pattern ?? '')}
        {count !== null && ` · ${count} ${t('aichat.toolMatches')}`}
        {parsed?.truncated === true && ` · ${t('aichat.toolTruncated')}`}
      </div>
      {matches.length > 0 && (
        <div className="chat-toolcard__matches">
          {matches.slice(0, 12).map((match, index) => (
            <div key={index} className="chat-toolcard__match">
              <span className="cx-muted chat-toolcard__matchref">
                {String(match.path ?? '')}:{String(match.line ?? '')}
              </span>
              <span className="chat-toolcard__matchtext">{String(match.text ?? '')}</span>
            </div>
          ))}
          {matches.length > 12 && (
            <div className="cx-muted chat-toolcard__more">+{matches.length - 12}</div>
          )}
        </div>
      )}
    </>
  )
}

function GlobBody({ activity }: { activity: ToolActivity }) {
  const { t } = useTranslation()
  const parsed = parseToolOutput(activity.output)
  const matches = Array.isArray(parsed?.matches) ? parsed!.matches as unknown[] : []
  return (
    <>
      <div className="cx-muted chat-toolcard__meta">
        {String(activity.args.pattern ?? '')}
        {typeof parsed?.count === 'number' && ` · ${parsed.count} ${t('aichat.toolFiles')}`}
      </div>
      {matches.length > 0 && (
        <div className="chat-toolcard__matches">
          {matches.slice(0, 15).map((match, index) => (
            <div key={index} className="chat-toolcard__match chat-toolcard__match--file">{String(match)}</div>
          ))}
          {matches.length > 15 && (
            <div className="cx-muted chat-toolcard__more">+{matches.length - 15}</div>
          )}
        </div>
      )}
    </>
  )
}

function ExecBody({ activity }: { activity: ToolActivity }) {
  const { t } = useTranslation()
  const parsed = parseToolOutput(activity.output)
  const exitCode = typeof parsed?.exitCode === 'number' ? parsed.exitCode : null
  return (
    <>
      <pre className="chat-toolcard__command">{String(activity.args.command ?? '')}</pre>
      {exitCode !== null && (
        <div className={cn('cx-muted chat-toolcard__meta',
          exitCode !== 0 && 'chat-toolcard__meta--error')}>
          {t('aichat.toolExitCode')}: {exitCode}
        </div>
      )}
      <PreviewOutput output={typeof parsed?.output === 'string' ? parsed.output : activity.output} />
    </>
  )
}

function TodoBody({ activity }: { activity: ToolActivity }) {
  const { t } = useTranslation()
  const steps: TodoStep[] = todosFromOutput(activity.output) ?? []
  const done = steps.filter(step => step.status === 'completed').length
  const current = steps.find(step => step.status === 'in_progress')
  if (steps.length === 0) return <PreviewOutput output={activity.output} />
  return (
    <div className="chat-toolcard__todos">
      <div className="cx-muted chat-toolcard__meta">
        <ListChecks size={12} />
        {done}/{steps.length}
        {current && ` · ${t('aichat.todoCurrent')}: ${current.content}`}
      </div>
      {steps.map((step, index) => (
        <div key={index} className={cn('chat-toolcard__todo',
          step.status === 'completed' && 'chat-toolcard__todo--done',
          step.status === 'in_progress' && 'chat-toolcard__todo--active')}>
          {step.status === 'completed' ? <Check size={13} />
            : step.status === 'in_progress' ? <CircleDashed size={13} />
            : <span className="chat-toolcard__todo-dot" />}
          <span>{step.content}</span>
        </div>
      ))}
    </div>
  )
}

function ExploreBody({ activity }: { activity: ToolActivity }) {
  const parsed = parseToolOutput(activity.output)
  const report = typeof parsed?.report === 'string' ? parsed.report : ''
  if (!report) return <PlainOutputBody activity={activity} />
  return <pre className="chat-toolcard__report">{report}</pre>
}

function PlainOutputBody({ activity }: { activity: ToolActivity }) {
  return <PreviewOutput output={activity.output} />
}

function FallbackBody({ activity, onOpenWorkspaceFile }: {
  activity: ToolActivity
  onOpenWorkspaceFile: (path: string) => void
}) {
  const { t } = useTranslation()
  const entries = Object.entries(activity.args)
    .filter(([, value]) => typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean')
    .slice(0, 6)
  return (
    <>
      {entries.length > 0 && (
        <div className="chat-toolcard__args">
          {entries.map(([key, value]) => (
            <div key={key} className="chat-toolcard__arg">
              <span className="cx-muted">{key}</span>
              <code>{String(value).length > 160 ? String(value).slice(0, 160) + '…' : String(value)}</code>
            </div>
          ))}
        </div>
      )}
      {activity.path && <PathChip path={activity.path} onOpenWorkspaceFile={onOpenWorkspaceFile} />}
      <PreviewOutput output={activity.output} />
      {activity.output && (
        <div className="cx-muted chat-toolcard__meta">{t('aichat.toolRawResult')}</div>
      )}
    </>
  )
}

/** First ~24 lines of a tool's text output with a "+n more" affordance. */
function PreviewOutput({ output }: { output: string }) {
  const { t } = useTranslation()
  if (!output) return null
  const lines = output.replace(/\n+$/, '').split('\n')
  const visible = lines.slice(0, 24)
  return (
    <pre className="chat-toolcard__output">
      {visible.join('\n')}
      {lines.length > visible.length && `\n… +${lines.length - visible.length} ${t('aichat.toolMoreLines')}`}
    </pre>
  )
}
