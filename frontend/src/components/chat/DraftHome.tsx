import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BookOpen, Bug, Code, FolderSearch } from 'lucide-react'
import logoUrl from '@/assets/infinia-logo.svg'
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

/** Hardcoded suggestion pool (ZCode featureSuggestedPrompts pattern) — FengYu flavor. */
const DRAFT_PROMPTS = [
  { icon: FolderSearch, labelKey: 'aichat.draftPrompt.workspaceLabel', promptKey: 'aichat.draftPrompt.workspacePrompt' },
  { icon: Code, labelKey: 'aichat.draftPrompt.scriptLabel', promptKey: 'aichat.draftPrompt.scriptPrompt' },
  { icon: BookOpen, labelKey: 'aichat.draftPrompt.explainLabel', promptKey: 'aichat.draftPrompt.explainPrompt' },
  { icon: Bug, labelKey: 'aichat.draftPrompt.debugLabel', promptKey: 'aichat.draftPrompt.debugPrompt' },
] as const

/** Suggested-prompt chips row; ZCode renders it under the draft composer. */
export function DraftPrompts() {
  const { t } = useTranslation()
  return (
    <div className="chat-draft-prompts">
      {DRAFT_PROMPTS.map((prompt, index) => (
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
