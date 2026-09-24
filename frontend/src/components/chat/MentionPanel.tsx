import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Code2, FileImage, FileText, Folder, Wand2, Workflow } from 'lucide-react'
import type { MentionOption, MentionSection } from '@/lib/mentionSearch'
import { cn } from '@/lib/utils'

/**
 * Completion panel (ZCode's MentionPanel structure): group headers when more than one group,
 * 34px option rows, hover independent of the keyboard-selected index. The parent owns
 * selection state; this panel renders and reports mouse interactions (mousedown prevents
 * editor focus loss).
 */
export function optionIcon(option: MentionOption) {
  if (option.category === 'skill') return <Wand2 size={15} />
  if (option.category === 'plugin') return <Code2 size={15} />
  if (option.category === 'flow') return <Workflow size={15} />
  if (option.isDirectory) return <Folder size={15} />
  const ext = option.label.split('.').pop()?.toLowerCase() ?? ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'pdf'].includes(ext)) return <FileImage size={15} />
  return <FileText size={15} />
}

export default function MentionPanel({ sections, selectedIndex, loading, emptyLabel, onSelect, onHover }: {
  sections: MentionSection[]
  selectedIndex: number
  loading: boolean
  emptyLabel: string
  onSelect: (option: MentionOption) => void
  onHover: (index: number) => void
}) {
  const { t } = useTranslation()
  const listRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    listRef.current?.querySelector('[data-selected="true"]')?.scrollIntoView({ block: 'nearest' })
  }, [selectedIndex])

  const categoryLabels: Record<string, string> = {
    file: t('aichat.mentionFiles'),
    plugin: t('aichat.mentionPlugins'),
    skill: t('aichat.mentionSkills'),
    flow: t('aichat.mentionFlows'),
  }

  let flatIndex = -1
  return (
    <div className="cx-card mention-panel" role="listbox" aria-label={t('aichat.mentionPanel')}>
      <div className="mention-panel__list" ref={listRef}>
        {sections.map(section => (
          <div key={section.category}>
            {sections.length > 1 && (
              <div className="mention-panel__header" aria-hidden="true">
                {categoryLabels[section.category] ?? section.category}
              </div>
            )}
            {section.options.map(option => {
              flatIndex += 1
              const index = flatIndex
              const selected = index === selectedIndex
              return (
                <button
                  key={option.id}
                  role="option"
                  aria-selected={selected}
                  data-selected={selected}
                  className={cn('mention-panel__option', { 'mention-panel__option--selected': selected })}
                  onMouseDown={event => event.preventDefault()}
                  onMouseEnter={() => onHover(index)}
                  onClick={() => onSelect(option)}
                >
                  <span className="mention-panel__icon">{optionIcon(option)}</span>
                  <span className="mention-panel__label">{option.label}</span>
                  <span className="cx-muted mention-panel__description" title={option.description}>{option.description}</span>
                </button>
              )
            })}
          </div>
        ))}
        {loading && <div className="cx-muted mention-panel__status"><span className="cx-spin" /> {t('common.loading')}</div>}
        {!loading && sections.length === 0 && <div className="cx-muted mention-panel__status">{emptyLabel}</div>}
      </div>
    </div>
  )
}
