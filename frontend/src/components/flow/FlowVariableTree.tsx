import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, Copy } from 'lucide-react'
import {
  flowTypeColor,
  flowTypeCompatible,
  type FlowOutputField,
  type FlowValueType,
} from '@/lib/flowInspectorModel'

/**
 * The reference picker behind the inspector's Reference source mode (port of the
 * Vue FlowVariableTree): workflow inputs first, then per upstream node its input
 * tree, complete-result row, and output tree. Rows recurse through declared
 * children; type-compatible rows are bindable, mismatched ones grayed with a
 * tooltip; click binds, drag carries the `application/x-fengyu-ref` payload.
 */

export interface VariableTreeSelection {
  kind: 'input' | 'node'
  nodeId?: string
  source?: 'input' | 'result'
  path?: string
  /** Declared type at the selected path (type-compat display). */
  type?: FlowValueType
}

/** The wire token a selection stands for — shared by copy and expression insertion. */
function selectionToken(selection: VariableTreeSelection, field: FlowOutputField): string {
  if (selection.kind === 'input') {
    const path = field.path.startsWith('.') ? field.path.slice(1) : field.path
    return `{{inputs.${path}}}`
  }
  return `{{node.${selection.nodeId}.${selection.source}${field.path}}}`
}

interface UpstreamGroup {
  nodeId: string
  title: string
  inputs: FlowOutputField[]
  outputs: FlowOutputField[]
}

export function FlowVariableTree(props: {
  /** The receiving node's expected input type (compatibility highlighting). */
  targetType?: FlowValueType | null
  /**
   * 'bind' (default) picks a whole-field reference; 'insert' emits the token to
   * the onInsert callback instead (expression editor, cursor-position insert),
   * and disables type graying — an expression can embed any token.
   */
  mode?: 'bind' | 'insert'
  workflowInputs: FlowOutputField[]
  upstreamNodes: Array<{ id: string; title: string; inputs: FlowOutputField[]; outputs: FlowOutputField[] }>
  onPick: (selection: VariableTreeSelection) => void
  onInsert?: (token: string) => void
}) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const query = search.trim().toLowerCase()

  const groups = useMemo<UpstreamGroup[]>(() => props.upstreamNodes.map((node) => ({
    nodeId: node.id,
    title: node.title,
    inputs: node.inputs,
    outputs: node.outputs,
  })), [props.upstreamNodes])

  const filterFields = (fields: FlowOutputField[]): FlowOutputField[] => {
    if (!query) return fields
    return fields.flatMap((field) => {
      const children = field.children ? filterFields(field.children) : []
      const selfHit = field.name.toLowerCase().includes(query)
        || field.title.toLowerCase().includes(query)
      return selfHit || children.length ? [{ ...field, children: children.length ? children : field.children }] : []
    })
  }

  /**
   * Auto-wire suggestion: when the receiving field's type is known, compatible leaves
   * float to the top of each list (stable otherwise). Search results keep authored
   * order — a query already names exactly what the user wants.
   */
  const suggestFields = (fields: FlowOutputField[]): FlowOutputField[] => {
    if (query || insertMode || !props.targetType) return fields
    const rank = (field: FlowOutputField) =>
      flowTypeCompatible(props.targetType!, field.type) ? 0 : 1
    return fields
      .map((field, index) => ({ field, index }))
      .sort((a, b) => rank(a.field) - rank(b.field) || a.index - b.index)
      .map((entry) => entry.field)
  }

  const insertMode = props.mode === 'insert'

  const renderRow = (field: FlowOutputField, selection: VariableTreeSelection) => {
    const compatible = insertMode
      || props.targetType == null
      || flowTypeCompatible(props.targetType, selection.type ?? field.type)
    const label = `${selection.kind === 'input' ? t('agent.workflowInputSource') : t('agent.nodeOutputSource')} · ${field.title}`
    const activate = () => {
      if (insertMode) {
        props.onInsert?.(selectionToken(selection, field))
        return
      }
      if (compatible) props.onPick(selection)
    }
    return (
      <div key={`${selection.nodeId ?? 'wf'}:${selection.source}:${field.path}`} className="flow-vtree__row-wrap">
        <div
          className={`flow-vtree__row${compatible ? '' : ' flow-vtree__row--mismatch'}`}
          title={compatible
            ? (insertMode ? t('agent.variableTreeInsertHint') : undefined)
            : t('agent.typeMismatchHint', {
              expected: props.targetType ?? 'any',
              actual: selection.type ?? field.type,
            })}
          draggable={compatible && !insertMode}
          onDragStart={(event) => {
            event.dataTransfer.setData('application/x-fengyu-ref', JSON.stringify(selection))
            event.dataTransfer.effectAllowed = 'copy'
          }}
          onClick={activate}
          role="treeitem"
          aria-label={label}
        >
          <span className="flow-vtree__dot" style={{ background: flowTypeColor(selection.type ?? field.type) }} />
          <span className="flow-vtree__name">{field.title}</span>
          <span className="flow-vtree__type">{t(`agent.flowType.${selection.type ?? field.type}`)}</span>
          <button
            className="cx-iconbtn cx-iconbtn--sm flow-vtree__copy"
            title={t('agent.copyReferencePath')}
            onClick={(event) => {
              event.stopPropagation()
              void navigator.clipboard?.writeText(selectionToken(selection, field))
            }}
          >
            <Copy size={12} />
          </button>
        </div>
        {field.children?.map((child) => {
          // Child paths concatenate verbatim: `.a` + `.b` → `.a.b`; `.a` + `[0]` → `.a[0]`.
          const childSelection: VariableTreeSelection = {
            ...selection,
            path: `${field.path}${child.path}`,
            type: child.type,
          }
          return renderRow(child, childSelection)
        })}
      </div>
    )
  }

  const inputGroups = suggestFields(filterFields(props.workflowInputs))
  const shownGroups = groups.map((group) => ({
    ...group,
    inputs: suggestFields(filterFields(group.inputs)),
    outputs: suggestFields(filterFields(group.outputs)),
  }))

  return (
    <div className="flow-vtree" role="tree" aria-label={t('agent.searchVariables')}>
      <input
        className="cx-input flow-vtree__search"
        placeholder={t('agent.searchVariables')}
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      {!inputGroups.length && !shownGroups.length && (
        <p className="cx-muted flow-vtree__empty">{t('agent.variableTreeEmpty')}</p>
      )}
      {inputGroups.length > 0 && (
        <TreeGroup label={t('agent.workflowInputSource')} defaultOpen>
          {inputGroups.map((field) => renderRow(field, {
            kind: 'input', path: field.path.replace(/^\./, ''), type: field.type,
          }))}
        </TreeGroup>
      )}
      {shownGroups.map((group) => (
        <TreeGroup key={group.nodeId} label={group.title} defaultOpen={!query}>
          {group.inputs.map((field) => renderRow(field, {
            kind: 'node', nodeId: group.nodeId, source: 'input',
            path: field.path, type: field.type,
          }))}
          {group.outputs.length > 0 && (
            <div
              className="flow-vtree__row"
              title={insertMode ? t('agent.variableTreeInsertHint') : undefined}
              onClick={() => {
                const selection: VariableTreeSelection = { kind: 'node', nodeId: group.nodeId, source: 'result', path: '', type: 'object' }
                if (insertMode) props.onInsert?.(selectionToken(selection, { name: '', title: '', path: '', type: 'object', examples: [] }))
                else props.onPick(selection)
              }}
              role="treeitem"
            >
              <span className="flow-vtree__dot" style={{ background: flowTypeColor('object') }} />
              <span className="flow-vtree__name">{t('agent.completeResult')}</span>
              <span className="flow-vtree__type">{t('agent.flowType.object')}</span>
            </div>
          )}
          {group.outputs.map((field) => renderRow(field, {
            kind: 'node', nodeId: group.nodeId, source: 'result',
            path: field.path, type: field.type,
          }))}
        </TreeGroup>
      ))}
      <p className="cx-muted flow-vtree__hint">
        {insertMode ? t('agent.variableTreeInsertHint') : t('agent.variableTreeHint')}
      </p>
    </div>
  )
}

function TreeGroup(props: { label: string; defaultOpen?: boolean; children: React.ReactNode }) {
  const [open, setOpen] = useState(!!props.defaultOpen)
  return (
    <div className="flow-vtree__group">
      <button className="flow-vtree__group-head" onClick={() => setOpen(!open)}>
        {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
        <span>{props.label}</span>
      </button>
      {open && <div className="flow-vtree__group-body">{props.children}</div>}
    </div>
  )
}
