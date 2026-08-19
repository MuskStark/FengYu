<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  flowTypeColor,
  flowTypeCompatible,
  normalizeFlowType,
  workflowNodeTitle,
  workflowOutputTree,
  type FlowOutputField,
  type FlowValueType,
  type WorkflowFlowNode,
  type WorkflowSchemaProperty,
} from './workflow'

/**
 * The reference picker (Dify's getNodeAvailableVars + n8n's schema view): upstream
 * outputs as a recursive tree, grouped by source node, filtered by the target
 * input's expected type. Rows are clickable (bind), draggable (drag-to-map onto
 * an input), and carry a copy-path affordance for the expression editor.
 */
const props = defineProps<{
  nodes: WorkflowFlowNode[]
  workflowSchemaFields: Array<[string, WorkflowSchemaProperty]>
  /** Type the target input expects; mismatched rows gray out with a reason. */
  expectedType?: string | null
  disabled?: boolean
}>()
const emit = defineEmits<{
  select: [selection: { kind: 'input' | 'node'; nodeId?: string; path?: string; type: FlowValueType }]
}>()

const { t } = useI18n()
const search = ref('')
const expanded = ref(new Set<string>())

interface TreeRow {
  key: string
  depth: number
  title: string
  path: string
  type: FlowValueType
  examples: unknown[]
  expandable: boolean
  nodeId?: string
  inputName?: string
}

function flattenFields(nodeId: string, fields: FlowOutputField[], depth: number, out: TreeRow[]): void {
  for (const field of fields) {
    const key = `${nodeId}${field.path}`
    out.push({
      key,
      depth,
      title: field.title,
      path: field.path,
      type: field.type,
      examples: field.examples,
      expandable: !!field.children?.length,
      nodeId,
    })
    if (field.children?.length && expanded.value.has(key)) {
      flattenFields(nodeId, field.children, depth + 1, out)
    }
  }
}

const searchNeedle = computed(() => search.value.trim().toLocaleLowerCase())
function matchesSearch(row: TreeRow): boolean {
  if (!searchNeedle.value) return true
  return `${row.title} ${row.path}`.toLocaleLowerCase().includes(searchNeedle.value)
}

const inputRows = computed<TreeRow[]>(() => props.workflowSchemaFields
  .filter(([name, schema]) => !searchNeedle.value
    || `${schema.title ?? ''} ${name}`.toLocaleLowerCase().includes(searchNeedle.value))
  .map(([name, schema]) => ({
    key: `input::${name}`,
    depth: 0,
    title: schema.title || name,
    path: name,
    type: (schema.format === 'fengyu-file' ? 'file' : schema.type) as FlowValueType,
    examples: [],
    expandable: false,
    inputName: name,
  })))

const nodeGroups = computed(() => {
  const groups: Array<{ node: WorkflowFlowNode; rows: TreeRow[] }> = []
  for (const node of props.nodes) {
    const rows: TreeRow[] = []
    // Whole-result row: binding the complete output object.
    rows.push({
      key: `${node.id}::complete`,
      depth: 0,
      title: t('agent.completeResult'),
      path: '',
      type: 'object',
      examples: [],
      expandable: false,
      nodeId: node.id,
    })
    flattenFields(node.id, workflowOutputTree(node), 0, rows)
    groups.push({ node, rows })
  }
  return groups
})

/** Every field fully expanded — used while searching so matches are never hidden. */
function allRowsFlat(node: WorkflowFlowNode): TreeRow[] {
  const saved = expanded.value
  expanded.value = new Set()
  const rows: TreeRow[] = [{
    key: `${node.id}::complete`,
    depth: 0,
    title: t('agent.completeResult'),
    path: '',
    type: 'object',
    examples: [],
    expandable: false,
    nodeId: node.id,
  }]
  flattenFields(node.id, workflowOutputTree(node), 0, rows)
  expanded.value = saved
  return rows
}

const visibleGroups = computed(() => nodeGroups.value
  .map((group) => ({
    node: group.node,
    rows: searchNeedle.value
      ? allRowsFlat(group.node).filter((row) => matchesSearch(row))
      : group.rows,
  }))
  .filter((group) => group.rows.length))

function compatible(row: TreeRow): boolean {
  if (row.inputName) {
    return !props.expectedType || flowTypeCompatible(props.expectedType, row.type)
  }
  return !props.expectedType || flowTypeCompatible(props.expectedType, row.type)
}

function typeLabel(row: TreeRow): string {
  return t(`agent.flowType.${normalizeFlowType(row.type)}`)
}

function exampleText(row: TreeRow): string {
  const example = row.examples[0]
  if (example === undefined || example === null) return ''
  const text = typeof example === 'string' ? example : JSON.stringify(example)
  return text.length > 40 ? `${text.slice(0, 37)}…` : text
}

function toggleExpand(row: TreeRow) {
  const next = new Set(expanded.value)
  if (next.has(row.key)) next.delete(row.key)
  else next.add(row.key)
  expanded.value = next
}

function selectRow(row: TreeRow) {
  if (props.disabled || !compatible(row)) return
  if (row.inputName) {
    emit('select', { kind: 'input', path: row.inputName, type: row.type })
    return
  }
  emit('select', { kind: 'node', nodeId: row.nodeId, path: row.path, type: row.type })
}

function copyRow(row: TreeRow) {
  const reference = row.inputName
    ? `{{inputs.${row.inputName}}}`
    : `{{node.${row.nodeId}.result${row.path}}}`
  void navigator.clipboard?.writeText(reference)
}

function onRowDragStart(event: DragEvent, row: TreeRow) {
  if (!event.dataTransfer || props.disabled || !compatible(row)) return
  const payload = row.inputName
    ? { kind: 'input', path: row.inputName, type: row.type }
    : { kind: 'node', nodeId: row.nodeId, path: row.path, type: row.type }
  event.dataTransfer.setData('application/x-fengyu-ref', JSON.stringify(payload))
  event.dataTransfer.effectAllowed = 'copy'
}
</script>

<template>
  <div class="fvt">
    <div class="fvt__search">
      <i class="mdi mdi-magnify" />
      <input v-model="search" :placeholder="t('agent.searchVariables')" :aria-label="t('agent.searchVariables')">
    </div>
    <div class="fvt__body">
      <div v-if="inputRows.length" class="fvt__group">
        <div class="fvt__group-head">
          <i class="mdi mdi-form-textbox" />
          <span>{{ t('agent.workflowInputSource') }}</span>
        </div>
        <div
          v-for="row in inputRows"
          :key="row.key"
          class="fvt__row"
          :class="{ 'fvt__row--muted': !compatible(row) }"
          :style="{ paddingInlineStart: `${8 + row.depth * 14}px` }"
          :title="compatible(row) ? t('agent.bindThisValue') : t('agent.typeMismatchHint', { type: typeLabel(row) })"
          draggable="true"
          @dragstart="onRowDragStart($event, row)"
          @click="selectRow(row)"
        >
          <span class="fvt__dot" :style="{ background: flowTypeColor(row.type) }" />
          <span class="fvt__name">{{ row.title }}</span>
          <span class="fvt__type">{{ typeLabel(row) }}</span>
          <button class="fvt__copy" :title="t('agent.copyReferencePath')" @click.stop="copyRow(row)"><i class="mdi mdi-content-copy" /></button>
        </div>
      </div>
      <div v-for="group in visibleGroups" :key="group.node.id" class="fvt__group">
        <div class="fvt__group-head">
          <i class="mdi mdi-hammer-wrench" />
          <span>{{ workflowNodeTitle(group.node) }}</span>
        </div>
        <div
          v-for="row in group.rows"
          :key="row.key"
          class="fvt__row"
          :class="{ 'fvt__row--muted': !compatible(row), 'fvt__row--branch': row.expandable }"
          :style="{ paddingInlineStart: `${8 + row.depth * 14}px` }"
          :title="compatible(row)
            ? exampleText(row) ? `${t('agent.exampleLabel')}: ${exampleText(row)}` : t('agent.bindThisValue')
            : t('agent.typeMismatchHint', { type: typeLabel(row) })"
          draggable="true"
          @dragstart="onRowDragStart($event, row)"
          @click="row.expandable ? toggleExpand(row) : selectRow(row)"
        >
          <button v-if="row.expandable" class="fvt__expand" :title="t('agent.toggleFields')" @click.stop="toggleExpand(row)">
            <i class="mdi" :class="expanded.has(row.key) ? 'mdi-menu-down' : 'mdi-menu-right'" />
          </button>
          <span v-else class="fvt__dot" :style="{ background: flowTypeColor(row.type) }" />
          <span class="fvt__name">{{ row.title }}</span>
          <span class="fvt__type">{{ typeLabel(row) }}</span>
          <button class="fvt__copy" :title="t('agent.copyReferencePath')" @click.stop="copyRow(row)"><i class="mdi mdi-content-copy" /></button>
        </div>
      </div>
      <div v-if="!visibleGroups.length && !inputRows.length" class="fvt__empty">
        {{ t('agent.variableTreeEmpty') }}
      </div>
    </div>
    <p class="fvt__hint">{{ t('agent.variableTreeHint') }}</p>
  </div>
</template>

<style scoped>
.fvt {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
  background: rgb(var(--v-theme-surface));
}

.fvt__search {
  display: flex;
  gap: 7px;
  align-items: center;
  padding: 0 8px;
  border-bottom: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px 8px 0 0;
  background: rgb(var(--v-theme-surface-container));
}

.fvt__search input {
  width: 100%;
  min-height: 32px;
  color: inherit;
  border: 0;
  outline: 0;
  background: transparent;
}

.fvt__body {
  max-height: 280px;
  overflow-y: auto;
  padding: 4px 0;
}

.fvt__group { margin-bottom: 4px; }

.fvt__group-head {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 6px 8px 3px;
  color: rgba(var(--v-theme-on-surface), .62);
  font-size: 10px;
  font-weight: 700;
}

.fvt__group-head i { font-size: 13px; color: rgb(var(--v-theme-primary)); }

.fvt__row {
  display: flex;
  gap: 6px;
  align-items: center;
  min-height: 28px;
  padding-block: 2px;
  padding-inline-end: 6px;
  font-size: 11px;
  cursor: pointer;
}

.fvt__row:hover { background: rgba(var(--v-theme-primary), .08); }

.fvt__row--muted {
  opacity: .45;
  cursor: not-allowed;
}

.fvt__row--branch .fvt__name { font-weight: 650; }

.fvt__dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.fvt__expand {
  display: grid;
  place-items: center;
  width: 16px;
  height: 20px;
  padding: 0;
  border: 0;
  background: transparent;
  color: rgba(var(--v-theme-on-surface), .6);
  cursor: pointer;
}

.fvt__name {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fvt__type {
  flex: 0 0 auto;
  color: rgba(var(--v-theme-on-surface), .5);
  font-size: 9px;
}

.fvt__copy {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: rgba(var(--v-theme-on-surface), .45);
  cursor: pointer;
}

.fvt__copy:hover { color: rgb(var(--v-theme-primary)); background: rgba(var(--v-theme-primary), .1); }

.fvt__empty {
  padding: 16px 10px;
  color: rgba(var(--v-theme-on-surface), .55);
  font-size: 11px;
  text-align: center;
}

.fvt__hint {
  margin: 0;
  padding: 6px 8px;
  color: rgba(var(--v-theme-on-surface), .5);
  font-size: 9px;
  line-height: 1.4;
  border-top: 1px solid rgb(var(--v-theme-outline-variant));
}
</style>
