import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Brain, ChevronRight } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * Thinking/reasoning block: collapsed header shows a shimmer
 * "thinking…" line while streaming (no spinner — a deliberate perf choice) with the live
 * last line of the stream; the elapsed timer only ticks while expanded; the block
 * auto-collapses when the turn settles UNLESS the user opened it manually.
 */
export default function ThinkingBlock({ text, streaming }: { text: string; streaming: boolean }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const startRef = useRef(Date.now())

  // Timer ticks only while open AND streaming (perf: no interval churn while collapsed).
  useEffect(() => {
    if (!open || !streaming) return
    const timer = window.setInterval(() => {
      setElapsed(Math.round((Date.now() - startRef.current) / 1000))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [open, streaming])

  if (!text) return null
  const lastLine = lastNonEmptyLine(text)

  return (
    <div className={cn('chat-thinking2', open && 'chat-thinking2--open')}>
      <button className="chat-thinking2__head" onClick={() => setOpen(value => !value)} aria-expanded={open}>
        <Brain size={14} className={cn('chat-thinking2__icon', streaming && 'chat-thinking2__icon--live')} />
        {streaming ? (
          <span className="chat-thinking2__title chat-shimmer">{t('aichat.thinkingLive')}</span>
        ) : (
          <span className="chat-thinking2__title">{t('aichat.thinking')}</span>
        )}
        {open && streaming && <span className="cx-muted chat-thinking2__elapsed">{elapsed}s</span>}
        {!open && streaming && lastLine && (
          <span className="cx-muted chat-thinking2__lastline">{lastLine}</span>
        )}
        <ChevronRight size={14} className={cn('chat-thinking2__chevron', open && 'chat-thinking2__chevron--open')} />
      </button>
      {open && (
        // Plain text (not markdown) while live — markdown re-parse per token is the thing
        // this design exists to avoid; settled text keeps the same cheap rendering.
        <pre className="chat-thinking2__body">{text}</pre>
      )}
    </div>
  )
}

function lastNonEmptyLine(text: string): string {
  const lines = text.split('\n')
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim()
    if (line) return line.length > 120 ? line.slice(0, 120) + '…' : line
  }
  return ''
}
