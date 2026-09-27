import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Brain, ChevronRight } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * Thinking/reasoning block: collapsed header shows a shimmer
 * "thinking…" line while reasoning fragments are arriving (no spinner — a deliberate perf
 * choice) with the live last line of the stream; the elapsed timer only ticks while
 * expanded. The `streaming` prop is the store's per-span thinkingActive signal — NOT the
 * whole turn — so the header freezes to "thought for Ns" the moment the answer or a tool
 * call takes over, and a later tool-loop round that reasons again resumes the shimmer
 * with a fresh clock (the frozen number always describes the latest span). The frozen
 * duration is only shown when this mount observed a live span: a reloaded conversation
 * carries the text but not how long it took.
 */
export default function ThinkingBlock({ text, streaming }: { text: string; streaming: boolean }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [settledSeconds, setSettledSeconds] = useState<number | null>(null)
  const startRef = useRef<number | null>(null)
  const bodyRef = useRef<HTMLPreElement>(null)

  // Span clock: starts with the first fragment of a live span, freezes the duration when
  // the span ends (streaming flips false), and clears the start mark so the next span
  // restarts the clock instead of billing the tool-round gap to the model's thinking.
  useEffect(() => {
    if (streaming && text && startRef.current === null) {
      startRef.current = Date.now()
    } else if (!streaming && startRef.current !== null) {
      setSettledSeconds(Math.round((Date.now() - startRef.current) / 1000))
      startRef.current = null
    }
  }, [streaming, text])

  // Timer ticks only while open AND streaming (perf: no interval churn while collapsed).
  useEffect(() => {
    if (!open || !streaming) return
    const timer = window.setInterval(() => {
      if (startRef.current !== null) setElapsed(Math.round((Date.now() - startRef.current) / 1000))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [open, streaming])

  // While expanded and streaming, the body stays pinned to the newest reasoning.
  useEffect(() => {
    if (open && streaming && bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight
  }, [text, open, streaming])

  if (!text) return null
  const lastLine = lastNonEmptyLine(text)

  return (
    <div className={cn('chat-thinking2', open && 'chat-thinking2--open')}>
      <button className="chat-thinking2__head" onClick={() => setOpen(value => !value)} aria-expanded={open}>
        <Brain size={14} className={cn('chat-thinking2__icon', streaming && 'chat-thinking2__icon--live')} />
        {streaming ? (
          <span className="chat-thinking2__title chat-shimmer">{t('aichat.thinkingLive')}</span>
        ) : settledSeconds !== null ? (
          <span className="chat-thinking2__title">{t('aichat.thoughtFor', { seconds: settledSeconds })}</span>
        ) : (
          <span className="chat-thinking2__title">{t('aichat.thoughts')}</span>
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
        <pre className="chat-thinking2__body" ref={bodyRef}>{text}</pre>
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
