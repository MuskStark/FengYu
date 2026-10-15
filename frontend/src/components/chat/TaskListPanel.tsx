import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, ChevronUp, Circle, CircleDashed, ListChecks } from 'lucide-react'
import type { TodoStep } from '@/lib/toolActivity'
import { cn } from '@/lib/utils'

/**
 * Claude-style task dock: the LATEST todo_write's steps float as a pinned panel at
 * the transcript's top-right (anchored OUTSIDE the scroller, so it survives scroll),
 * advancing live as tool rounds complete. The header collapses it to a progress pill
 * — the pill keeps showing the count and a breathing dot while the turn runs. Hidden
 * entirely until some todo_write in the conversation produced parseable steps.
 */
export default function TaskListPanel({ steps, streaming }: {
  steps: TodoStep[]
  streaming: boolean
}) {
  const { t } = useTranslation()
  const [collapsed, setCollapsed] = useState(false)

  const done = steps.filter(step => step.status === 'completed').length
  const progress = `${done}/${steps.length}`

  if (collapsed) {
    return (
      <div className="chat-taskdock">
        <button
          type="button"
          className="chat-taskdock__pill"
          onClick={() => setCollapsed(false)}
          aria-label={t('aichat.taskListExpand')}
        >
          <ListChecks size={13} />
          <span>{t('aichat.taskList')}</span>
          <b>{progress}</b>
          <span className={cn('chat-taskdock__livedot', !streaming && 'chat-taskdock__livedot--idle')} aria-hidden="true" />
        </button>
      </div>
    )
  }

  return (
    <div className="chat-taskdock">
      <div className="chat-taskdock__panel">
        <button
          type="button"
          className="chat-taskdock__head"
          onClick={() => setCollapsed(true)}
          aria-expanded="true"
          aria-label={t('aichat.taskListCollapse')}
        >
          <ListChecks size={13} />
          <span>{t('aichat.taskList')}</span>
          <span className="chat-taskdock__count">{progress}</span>
          <ChevronUp size={13} className="chat-taskdock__chev" />
        </button>
        <div className="chat-taskdock__body">
          {steps.map((step, index) => (
            <div key={index} className={cn('chat-taskdock__row',
              step.status === 'completed' && 'chat-taskdock__row--done',
              step.status === 'in_progress' && 'chat-taskdock__row--cur')}>
              <span className="chat-taskdock__ico" aria-hidden="true">
                {step.status === 'completed' ? <Check size={14} />
                  : step.status === 'in_progress' ? <CircleDashed size={14} />
                  : <Circle size={14} />}
              </span>
              <span className="chat-taskdock__txt">{step.content}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
