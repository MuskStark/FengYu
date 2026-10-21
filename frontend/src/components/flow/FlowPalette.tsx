import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, FileText, GitBranch, Hammer, PlayCircle, Search, Send, Sparkles, X } from 'lucide-react'
import type { AgentTool } from '@/services/types'
import { humanizeToolName, workflowToolCategory } from '@/lib/flowDisplay'

/**
 * Left-rail node palette: search box + one collapsible group per task-verb category
 * (generate / judge / fetch / send / files), plus the Start entry. Items are dragged
 * onto the canvas (or clicked to append).
 */

const CATEGORY_ICONS: Record<string, typeof Hammer> = {
  generate: Sparkles,
  judge: GitBranch,
  fetch: Search,
  send: Send,
  files: FileText,
  other: Hammer,
}

/** Fixed group order: creation verbs (generate / branch) before capability verbs. */
const CATEGORY_ORDER = ['generate', 'judge', 'fetch', 'send', 'files', 'other']

export function categoryIcon(category: string): typeof Hammer {
  return CATEGORY_ICONS[category] ?? Hammer
}

export function FlowPalette(props: {
  tools: AgentTool[]
  hasStart: boolean
  disabled?: boolean
  onAdd: (tool: AgentTool) => void
  onAddStart: () => void
  onDragStart: (event: React.DragEvent, tool: AgentTool) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())

  const groups = useMemo(() => {
    const query = search.trim().toLocaleLowerCase()
    const filtered = query
      ? props.tools.filter((tool) =>
        // Match the humanized display name too: "Flow Llm" is what the row shows, but the
        // raw name is "flow_llm" — searching the visible label must find it.
        `${humanizeToolName(tool.name)} ${tool.name} ${tool.localizedDescription || tool.description}`
          .toLocaleLowerCase().includes(query))
      : props.tools
    const byCategory = new Map<string, AgentTool[]>()
    for (const tool of filtered) {
      const category = workflowToolCategory(tool)
      byCategory.set(category, [...(byCategory.get(category) ?? []), tool])
    }
    const rank = (category: string) => {
      const index = CATEGORY_ORDER.indexOf(category)
      return index < 0 ? CATEGORY_ORDER.length : index
    }
    return [...byCategory.entries()].sort((left, right) => rank(left[0]) - rank(right[0]))
  }, [props.tools, search])

  const toggleCategory = (category: string) => {
    setCollapsed((current) => {
      const next = new Set(current)
      if (next.has(category)) next.delete(category)
      else next.add(category)
      return next
    })
  }

  return (
    <div className="flow-palette">
      <div className="flow-palette__title">
        {t('agent.addNode')}
        <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('flows.close')} onClick={props.onClose}>
          <X size={16} />
        </button>
      </div>
      <label className="flow-palette__search">
        <Search size={14} />
        <input
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder={t('agent.searchNodes')}
          aria-label={t('agent.searchNodes')}
          autoFocus
        />
      </label>
      <p className="cx-muted flow-palette__hint">{t('flows.paletteHint')}</p>

      <section className="flow-palette__group">
        <div className="flow-palette__group-head flow-palette__group-head--static">
          <PlayCircle size={15} />
          <span>{t('agent.toolCategory.control')}</span>
          <small>1</small>
        </div>
        <div className="flow-palette__group-body">
          <button
            className="flow-palette__tool"
            disabled={props.disabled || props.hasStart}
            onClick={props.onAddStart}
          >
            <PlayCircle size={15} />
            <span>
              <strong>{t('agent.addStartNode')}</strong>
              <small>{props.hasStart ? t('agent.startNodeAlreadyExists') : t('agent.startNodeDescription')}</small>
            </span>
          </button>
        </div>
      </section>

      {groups.map(([category, tools]) => {
        const Icon = categoryIcon(category)
        return (
          <section key={category} className="flow-palette__group">
            <button className="flow-palette__group-head" onClick={() => toggleCategory(category)}>
              <Icon size={15} />
              <span>{t(`agent.toolCategory.${category}`)}</span>
              <small>{tools.length}</small>
              {collapsed.has(category)
                ? <ChevronRight size={14} className="flow-palette__chevron" />
                : <ChevronDown size={14} className="flow-palette__chevron" />}
            </button>
            {!collapsed.has(category) && (
              <div className="flow-palette__group-body">
                {tools.map((tool_) => (
                  <button
                    key={tool_.name}
                    className="flow-palette__tool"
                    disabled={props.disabled}
                    draggable
                    onDragStart={(event) => props.onDragStart(event, tool_)}
                    onClick={() => props.onAdd(tool_)}
                  >
                    <Hammer size={15} />
                    <span>
                      <strong>{humanizeToolName(tool_.name)}</strong>
                      <small>{tool_.localizedDescription || tool_.description}</small>
                    </span>
                  </button>
                ))}
              </div>
            )}
          </section>
        )
      })}

      {!groups.length && (
        <div className="cx-muted flow-palette__empty">
          {search.trim() ? t('agent.noMatchingTools') : t('agent.noTools')}
        </div>
      )}
    </div>
  )
}
