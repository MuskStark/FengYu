import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BookOpen, Bug, Code, FolderSearch } from 'lucide-react'
import logoUrl from '@/assets/infinia-logo.svg'
import { useAiSessionStore } from '@/stores/aiSession'
import '@/styles/chat.css'

/**
 * Draft-screen empty state, ported from ZCode's ConversationDraftEmptyState: a time-based
 * greeting over a faded logo watermark, plus the suggested-prompt chips row (ZCode's
 * ConversationDraftSuggestedPrompts). Chips seed the composer through the
 * `fengyu:composer-seed` DOM event — ChatComposer owns the editor and listens for it.
 * The greeting re-resolves at the next time boundary rather than on re-render only.
 */

const GREETING_BOUNDARY_HOURS = [5, 9, 12, 14, 18, 23]

function greetingKey(date: Date): string {
  const hour = date.getHours()
  if (hour >= 5 && hour < 9) return 'aichat.greeting.morningEarly'
  if (hour >= 9 && hour < 12) return 'aichat.greeting.morning'
  if (hour >= 12 && hour < 14) return 'aichat.greeting.noon'
  if (hour >= 14 && hour < 18) return 'aichat.greeting.afternoon'
  if (hour >= 18 && hour < 23) return 'aichat.greeting.evening'
  return 'aichat.greeting.lateNight'
}

/** Milliseconds until the next greeting boundary, so the label rolls over on its own. */
function nextGreetingDelayMs(date: Date): number {
  const upcoming = GREETING_BOUNDARY_HOURS.map(hour => {
    const boundary = new Date(date)
    boundary.setHours(hour, 0, 0, 0)
    return boundary
  })
  const tomorrowFirst = new Date(date)
  tomorrowFirst.setDate(tomorrowFirst.getDate() + 1)
  tomorrowFirst.setHours(GREETING_BOUNDARY_HOURS[0], 0, 0, 0)
  const next = upcoming.find(candidate => candidate.getTime() > date.getTime()) ?? tomorrowFirst
  return Math.max(1, next.getTime() - date.getTime())
}

export function seedComposerPrompt(text: string): void {
  window.dispatchEvent(new CustomEvent('fengyu:composer-seed', { detail: { text } }))
}

/** Suggestion pool (ZCode featureSuggestedPrompts pattern) — FengYu flavor. Rotates daily. */
const DRAFT_PROMPTS = [
  { icon: FolderSearch, labelKey: 'aichat.draftPrompt.workspaceLabel', promptKey: 'aichat.draftPrompt.workspacePrompt' },
  { icon: Code, labelKey: 'aichat.draftPrompt.scriptLabel', promptKey: 'aichat.draftPrompt.scriptPrompt' },
  { icon: BookOpen, labelKey: 'aichat.draftPrompt.explainLabel', promptKey: 'aichat.draftPrompt.explainPrompt' },
  { icon: Bug, labelKey: 'aichat.draftPrompt.debugLabel', promptKey: 'aichat.draftPrompt.debugPrompt' },
  { icon: Code, labelKey: 'aichat.draftPrompt.refactorLabel', promptKey: 'aichat.draftPrompt.refactorPrompt' },
  { icon: BookOpen, labelKey: 'aichat.draftPrompt.summarizeLabel', promptKey: 'aichat.draftPrompt.summarizePrompt' },
  { icon: Bug, labelKey: 'aichat.draftPrompt.testLabel', promptKey: 'aichat.draftPrompt.testPrompt' },
  { icon: FolderSearch, labelKey: 'aichat.draftPrompt.cleanupLabel', promptKey: 'aichat.draftPrompt.cleanupPrompt' },
] as const

/** How many chips render under the draft composer. */
const VISIBLE_PROMPTS = 4

/**
 * Rotating selection (ZCode's suggested-prompt rotation): a deterministic day-indexed
 * window over the pool, so the suggestions feel alive without flickering per render.
 * Workspace-bound conversations bias toward the first (workspace) chip.
 */
function rotatedPrompts(workspaceBound: boolean): typeof DRAFT_PROMPTS[number][] {
  const pool = [...DRAFT_PROMPTS]
  const dayIndex = Math.floor(Date.now() / 86_400_000)
  const offset = dayIndex % pool.length
  const rotated = [...pool.slice(offset), ...pool.slice(0, offset)]
  let selected = rotated.slice(0, VISIBLE_PROMPTS)
  if (workspaceBound) {
    const workspaceChip = pool[0]
    if (!selected.includes(workspaceChip)) {
      selected = [workspaceChip, ...selected.slice(0, VISIBLE_PROMPTS - 1)]
    }
  }
  return selected
}

/** Suggested-prompt chips row; ZCode renders it under the draft composer. */
export function DraftPrompts() {
  const { t } = useTranslation()
  const workspaceBound = useAiSessionStore(state => Boolean(state.active()?.workspaceRoot))
  const prompts = useMemo(() => rotatedPrompts(workspaceBound), [workspaceBound])
  return (
    <div className="chat-draft-prompts">
      {prompts.map((prompt, index) => (
        <button
          key={prompt.labelKey}
          className="chat-draft-prompt zai-draft-prompt-waterfall"
          style={{ '--zai-draft-prompt-waterfall-delay': `${index * 65}ms` } as React.CSSProperties}
          onClick={() => seedComposerPrompt(t(prompt.promptKey))}
        >
          <prompt.icon size={15} />
          <span>{t(prompt.labelKey)}</span>
        </button>
      ))}
    </div>
  )
}

export default function DraftHome() {
  const { t } = useTranslation()
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    const timer = window.setTimeout(() => setNow(new Date()), nextGreetingDelayMs(now))
    return () => window.clearTimeout(timer)
  }, [now])

  return (
    <div className="chat-draft-hero">
      <span className="chat-draft-hero__watermark" aria-hidden="true">
        <img src={logoUrl} alt="" />
      </span>
      <div className="chat-draft-hero__greeting">{t(greetingKey(now))}</div>
    </div>
  )
}
