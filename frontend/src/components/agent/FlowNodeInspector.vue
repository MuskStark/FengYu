<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import type { FlowNodeInput } from '@/api/types'
import {
  flattenWorkflowOutputFields,
  humanizeWorkflowField,
  missingRequiredWorkflowInputs,
  wouldCreateCycle,
  type FlowCanvasEdge,
  type WorkflowFlowNode,
  type WorkflowSchemaProperty,
} from '@/components/agent/workflow'

/**
 * Flowise-style node configuration panel: opens on the right when a tool node
 * is selected. Every input can be typed manually, bound to a workflow input,
 * or bound to an upstream node's output (which auto-creates the edge).
 */
const props = defineProps<{
  node: WorkflowFlowNode
  nodes: WorkflowFlowNode[]
  edges: FlowCanvasEdge[]
  workflowSchemaFields: Array<[string, WorkflowSchemaProperty]>
  disabled?: boolean
}>()
const emit = defineEmits<{
  delete: []
  close: []
  /** Requests the parent to add the data-flow edge implied by a node-output binding. */
  link: [sourceId: string, targetId: string]
}>()

const { t } = useI18n()

interface InputSchema {
  type?: string
  title?: string
  description?: string
  default?: unknown
  enum?: unknown[]
  required?: string[]
  properties?: Record<string, InputSchema>
  items?: InputSchema
  'x-fengyu-advanced'?: boolean
  /** Render as a plain multiline string textarea. */
  'x-fengyu-multiline'?: boolean
  /** `excel` — render an analyze button beside this input; results feed the row pickers. */
  'x-fengyu-analyze'?: string
  /** `workbook-sheets` / `workbook-columns` — datalist candidates from the analysis. */
  'x-fengyu-options-from'?: string
}

const inputSchema = computed<InputSchema>(() => {
  try {
    return JSON.parse(props.node.data.tool.inputSchema || '{}') as InputSchema
  } catch {
    return {}
  }
})
/**
 * Descriptor-first rendering: when the node carries an explicit flow-node
 * declaration its inputs (widget config) drive the form, mapped onto the same
 * editor machinery; only legacy nodes fall back to the tool's JSON Schema.
 */
const inputFields = computed<Array<[string, InputSchema]>>(() => {
  const declared = props.node.data.descriptor?.inputs
  if (declared?.length) {
    return declared.map((input) => [input.name, widgetSchema(input)])
  }
  return Object.entries(inputSchema.value.properties ?? {})
    .filter(([, schema]) => !schema['x-fengyu-advanced']) as Array<[string, InputSchema]>
})

/** Maps a declared widget onto the editor schema vocabulary. */
function widgetSchema(input: FlowNodeInput): InputSchema {
  const base: InputSchema = { title: input.title, description: input.description, default: input.default }
  switch (input.widget) {
    case 'number':
      return { ...base, type: 'number' }
    case 'select':
      return { ...base, type: 'string', enum: input.options }
    case 'switch':
      return { ...base, type: 'boolean' }
    case 'textarea':
      return { ...base, type: 'string', 'x-fengyu-multiline': true }
    case 'json':
      // mono JSON editor: parses on change, so array/object args stay typed.
      return { ...base, type: 'object' }
    case 'analyze':
      return { ...base, type: 'string', 'x-fengyu-analyze': 'excel' }
    case 'rows': {
      const properties: Record<string, InputSchema> = {}
      for (const field of input.fields ?? []) {
        properties[field.name] = field.widget === 'number'
          ? { type: 'number', title: field.title }
          : field.widget === 'switch'
            ? { type: 'boolean', title: field.title }
            : field.widget === 'select'
              ? { type: 'string', title: field.title }
              : { type: 'string', title: field.title, 'x-fengyu-options-from': field.optionsFrom }
      }
      return { ...base, type: 'array', items: { type: 'object', properties } }
    }
    default:
      return { ...base, type: 'string' }
  }
}
/** Inputs folded behind "Advanced settings" (x-fengyu-advanced in the tool schema). */
const advancedInputFields = computed(() => Object.entries(inputSchema.value.properties ?? {})
  .filter(([, schema]) => schema['x-fengyu-advanced']))
const requiredInputs = computed(() => new Set(inputSchema.value.required ?? []))
const arguments_ = computed<Record<string, unknown>>(() => {
  try {
    const parsed = JSON.parse(props.node.data.argsText || '{}')
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
})
const availableSourceNodes = computed(() =>
  props.nodes.filter((node) => node.id !== props.node.id
    && !wouldCreateCycle(props.edges, node.id, props.node.id)))
const downstreamNodes = computed(() => {
  const targetIds = new Set(props.edges.filter((edge) => edge.source === props.node.id).map((edge) => edge.target))
  return props.nodes.filter((node) => targetIds.has(node.id))
})
/** Sheet → header columns of the workbook analyzed from the node's filePath input. */
const workbookAnalysis = ref<Record<string, string[]>>({})
const workbookAnalyzing = ref(false)
const workbookAnalysisError = ref<string | null>(null)

// Analysis is per node — reset when the inspector switches targets.
watch(() => props.node.id, () => {
  workbookAnalysis.value = {}
  workbookAnalyzing.value = false
  workbookAnalysisError.value = null
})

/**
 * Analyzes the workbook currently typed into filePath through the Excel plugin
 * (run-dialog parity), so entry rows can pick real sheet/column names instead
 * of typing blind. A dedicated canvas-<nodeId> session keeps the analysis from
 * touching chat/run split sessions.
 */
async function analyzeNodeWorkbook() {
  const raw = arguments_.value['filePath']
  const filePath = typeof raw === 'string' ? raw.trim() : ''
  if (!filePath || workbookAnalyzing.value) return
  workbookAnalyzing.value = true
  workbookAnalysisError.value = null
  try {
    // The UI-facing `analyze` RPC returns the full sheet→columns map (the AI-facing
    // excel_analyze tool returns sheet names only).
    const result = await api.invokePluginMethod<{
      success?: boolean
      sheets?: Array<{ name: string; columns?: Array<{ header?: string }> }>
    }>('fan.summer.excel', 'analyze', {
      session: `canvas-${props.node.id}`,
      sourceFile: filePath,
    })
    const sheets: Record<string, string[]> = {}
    for (const sheet of result?.sheets ?? []) {
      sheets[sheet.name] = (sheet.columns ?? [])
        .map((column) => column.header ?? '')
        .filter(Boolean)
    }
    workbookAnalysis.value = sheets
  } catch (e) {
    workbookAnalysis.value = {}
    workbookAnalysisError.value = e instanceof Error ? e.message : String(e)
  } finally {
    workbookAnalyzing.value = false
  }
}

/** Row layout: the first non-boolean field shares its line with boolean switches. */
function rowParts(schema: InputSchema): {
  first: [string, InputSchema] | null
  rest: Array<[string, InputSchema]>
  booleans: Array<[string, InputSchema]>
} {
  const entries = Object.entries(schema.items?.properties ?? {})
    .filter(([, child]) => !child['x-fengyu-advanced']) as Array<[string, InputSchema]>
  const booleans = entries.filter(([, child]) => child.type === 'boolean')
  const others = entries.filter(([, child]) => child.type !== 'boolean')
  return { first: others[0] ?? null, rest: others.slice(1), booleans }
}

/** Datalist candidates for an annotated field; column lists follow the row's sheet. */
function rowOptions(source: string | undefined, rowSheet?: unknown): string[] {
  if (source === 'workbook-sheets') return Object.keys(workbookAnalysis.value)
  if (source === 'workbook-columns') {
    const sheet = typeof rowSheet === 'string' ? rowSheet : ''
    const columns = sheet ? workbookAnalysis.value[sheet] : undefined
    // A row whose sheet is not (yet) picked still sees the union, so the user can
    // pre-fill a column and the sheet name after.
    return columns ?? [...new Set(Object.values(workbookAnalysis.value).flat())]
  }
  return []
}

const missingInputs = computed(() =>
  missingRequiredWorkflowInputs(props.node.data.tool.inputSchema, props.node.data.argsText))
const outputFields = computed(() => {
  try {
    const schema = JSON.parse(props.node.data.tool.outputSchema || '{}') as InputSchema
    return Object.entries(schema.properties ?? {}).filter(([name]) => name !== 'success' && name !== 'summary')
  } catch {
    return []
  }
})

/** True when two option lists would offer the same reference path. */
function sameField(a: Array<[string, unknown]>, b: Array<[string, unknown]>): boolean {
  return a.length === b.length && a.every(([name], i) => name === b[i][0])
}

/**
 * Candidate references one upstream node offers: its DECLARED output ports
 * (labeled as authored) plus every field of its output schema (flattened one
 * nesting level), deduplicated — the next node's inputs can bind to any of them.
 */
function toolOutputFields(node: WorkflowFlowNode): Array<[string, InputSchema]> {
  const schemaFields = flattenWorkflowOutputFields(node.data.tool.outputSchema)
    .map(([name, schema]) => [name, schema] as [string, InputSchema])
  const declared = (node.data.descriptor?.outputs ?? [])
    .map((port) => [port.name, { title: port.title, type: port.type } as InputSchema] as [string, InputSchema])
  if (sameField(declared, schemaFields)) return schemaFields
  const seen = new Set(declared.map(([name]) => name))
  const merged = [...declared]
  for (const field of schemaFields) {
    if (!seen.has(field[0])) {
      merged.push(field)
      seen.add(field[0])
    }
  }
  return merged
}

function setNodeArgument(name: string, value: unknown) {
  props.node.data.argsText = JSON.stringify({ ...arguments_.value, [name]: value }, null, 2)
}

function removeNodeArgument(name: string) {
  const next = { ...arguments_.value }
  delete next[name]
  props.node.data.argsText = JSON.stringify(next, null, 2)
}

function inputSource(name: string): string {
  const value = arguments_.value[name]
  if (typeof value !== 'string') return 'manual'
  const match = /^\{\{node\.([A-Za-z0-9_-]+)\.result((?:\.[A-Za-z0-9_-]+)+)?}}$/.exec(value)
  if (match) return `node::${match[1]}${match[2] ? `::${match[2].slice(1)}` : ''}`
  const workflowInput = /^\{\{inputs\.([A-Za-z0-9_-]+)}}$/.exec(value)
  return workflowInput ? `input::${workflowInput[1]}` : 'manual'
}

function changeInputSource(name: string, schema: InputSchema, event: Event) {
  const source = (event.target as HTMLSelectElement).value
  if (source.startsWith('input::')) {
    setNodeArgument(name, `{{inputs.${source.slice(7)}}}`)
    return
  }
  if (source.startsWith('node::')) {
    const [, nodeId, output] = source.split('::')
    setNodeArgument(name, `{{node.${nodeId}.result${output ? `.${output}` : ''}}}`)
    if (!props.edges.some((edge) => edge.source === nodeId && edge.target === props.node.id)) {
      emit('link', nodeId, props.node.id)
    }
    return
  }
  const current = arguments_.value[name]
  if (typeof current === 'string' && /^\{\{node\.[A-Za-z0-9_-]+\.result(?:\.[A-Za-z0-9_-]+)*}}$/.test(current)) {
    setNodeArgument(name, schema.default ?? emptySchemaValue(schema))
  }
}

function emptySchemaValue(schema: InputSchema): unknown {
  if (schema.type === 'array') return []
  if (schema.type === 'object') return {}
  if (schema.type === 'boolean') return false
  if (schema.type === 'integer' || schema.type === 'number') return 0
  return ''
}

function valueFromInput(schema: InputSchema, event: Event): unknown {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  if (schema.type === 'boolean' && target instanceof HTMLInputElement) return target.checked
  if (schema.type === 'integer' || schema.type === 'number') {
    // Number('') === 0: clearing the field removes the key instead of writing a 0.
    if (target.value === '') return undefined
    const parsed = Number(target.value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (schema.enum?.length) return schema.enum.find((option) => String(option) === target.value) ?? target.value
  return target.value
}

function updateSimpleInput(name: string, schema: InputSchema, event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  if (schema.type === 'array') {
    const items = target.value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean)
    setNodeArgument(name, schema.items?.type === 'integer' || schema.items?.type === 'number'
      ? items.map(Number)
      : items)
    return
  }
  setNodeArgument(name, valueFromInput(schema, event))
}

function updateObjectInput(name: string, event: Event) {
  try {
    setNodeArgument(name, JSON.parse((event.target as HTMLTextAreaElement).value || '{}'))
  } catch {
    // Keep the last valid value; the advanced JSON editor remains available for complex objects.
  }
}

function updateObjectField(name: string, childName: string, schema: InputSchema, event: Event) {
  const current = arguments_.value[name]
  const object = current && !Array.isArray(current) && typeof current === 'object'
    ? { ...current as Record<string, unknown> }
    : {}
  object[childName] = valueFromInput(schema, event)
  setNodeArgument(name, object)
}

function objectInputValue(name: string, childName: string): unknown {
  const value = arguments_.value[name]
  return value && !Array.isArray(value) && typeof value === 'object'
    ? (value as Record<string, unknown>)[childName] ?? ''
    : ''
}

function arrayObjectItems(name: string): Record<string, unknown>[] {
  const value = arguments_.value[name]
  return Array.isArray(value) ? value.filter((item) => item && typeof item === 'object') as Record<string, unknown>[] : []
}

function addArrayObjectItem(name: string) {
  setNodeArgument(name, [...arrayObjectItems(name), {}])
}

function removeArrayObjectItem(name: string, index: number) {
  setNodeArgument(name, arrayObjectItems(name).filter((_, itemIndex) => itemIndex !== index))
}

function updateArrayObjectField(name: string, index: number, childName: string, schema: InputSchema, event: Event) {
  const items = arrayObjectItems(name).map((item) => ({ ...item }))
  items[index] = { ...items[index], [childName]: valueFromInput(schema, event) }
  setNodeArgument(name, items)
}

function displayInputValue(name: string, schema: InputSchema): string | number {
  const value = arguments_.value[name]
  if (value === undefined || value === null) return ''
  if (schema.type === 'array') return Array.isArray(value) ? value.join(', ') : String(value)
  if (schema.type === 'object') return JSON.stringify(value, null, 2)
  return typeof value === 'number' ? value : String(value)
}
</script>

<template>
  <div class="flow-inspector">
    <div class="flow-inspector__title">
      {{ t('agent.nodeSettings') }}
      <span class="cx-row">
        <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.deleteNode')" @click="emit('delete')"><i class="mdi mdi-delete-outline" /></button>
        <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('flows.close')" @click="emit('close')"><i class="mdi mdi-close" /></button>
      </span>
    </div>
    <div v-if="node.data.tool.localizedDescription || node.data.tool.description" class="flow-inspector__intro">
      <i class="mdi mdi-information-outline" />
      <span>{{ node.data.tool.localizedDescription || node.data.tool.description }}</span>
    </div>
    <div v-if="!node.data.available" class="cx-alert cx-alert--error">
      <span class="cx-alert__body">{{ t('agent.toolUnavailable') }}</span>
    </div>

    <section class="flow-config-section">
      <div class="flow-config-section__heading">
        <h3><i class="mdi mdi-login-variant" /> {{ t('agent.inputConfig') }}</h3>
        <span v-if="missingInputs.length" class="flow-completion flow-completion--warn">{{ t('agent.missingInputs', { count: missingInputs.length }) }}</span>
        <span v-else class="flow-completion flow-completion--ready"><i class="mdi mdi-check" /> {{ t('agent.ready') }}</span>
      </div>
      <div v-if="!inputFields.length" class="cx-muted flow-config-empty">
        {{ t('agent.noInputRequired') }}
      </div>
      <div v-for="([name, schema]) in inputFields" :key="name" class="flow-argument">
        <div class="flow-argument__label">
          <span>{{ schema.title || humanizeWorkflowField(name) }}</span>
          <span v-if="requiredInputs.has(name)" class="flow-required">{{ t('agent.required') }}</span>
          <span class="flow-type-chip">{{ t(`agent.fieldType.${schema.type || 'string'}`) }}</span>
        </div>
        <small v-if="schema.description">{{ schema.description }}</small>
        <select
          v-if="workflowSchemaFields.length || availableSourceNodes.length"
          class="cx-input flow-source-select"
          :value="inputSource(name)"
          :disabled="disabled"
          @change="changeInputSource(name, schema, $event)"
        >
          <option value="manual">{{ t('agent.manualInput') }}</option>
          <optgroup v-if="workflowSchemaFields.length" :label="t('agent.workflowInputSource')">
            <option v-for="([inputName, inputSchema]) in workflowSchemaFields" :key="`input-${inputName}`" :value="`input::${inputName}`">{{ inputSchema.title || humanizeWorkflowField(inputName) }}</option>
          </optgroup>
          <optgroup
            v-for="source in availableSourceNodes"
            :key="`${source.id}-fields`"
            :label="t('agent.nodeOutputFields', { name: source.data.tool.name })"
          >
            <option :value="`node::${source.id}`">{{ t('agent.completeResult') }}</option>
            <option
              v-for="([outputName, outputSchema]) in toolOutputFields(source)"
              :key="`${source.id}-${outputName}`"
              :value="`node::${source.id}::${outputName}`"
            >
              {{ outputSchema.title || humanizeWorkflowField(outputName) }}
            </option>
          </optgroup>
        </select>

        <template v-if="inputSource(name) === 'manual'">
          <select
            v-if="schema.enum?.length"
            class="cx-input"
            :value="arguments_[name] ?? ''"
            :disabled="disabled"
            @change="updateSimpleInput(name, schema, $event)"
          >
            <option v-if="!requiredInputs.has(name)" value="">{{ t('agent.notSet') }}</option>
            <option v-for="option in schema.enum" :key="String(option)" :value="option">{{ option }}</option>
          </select>
          <label v-else-if="schema.type === 'boolean'" class="flow-boolean-input">
            <input
              type="checkbox"
              :checked="Boolean(arguments_[name])"
              :disabled="disabled"
              @change="updateSimpleInput(name, schema, $event)"
            >
            <span>{{ t('agent.enabled') }}</span>
          </label>
          <div v-else-if="schema.type === 'object' && schema.properties" class="flow-nested-fields">
            <label v-for="([childName, childSchema]) in Object.entries(schema.properties)" :key="childName">
              <span>{{ childSchema.title || humanizeWorkflowField(childName) }}</span>
              <input class="cx-input" :type="childSchema.type === 'number' || childSchema.type === 'integer' ? 'number' : 'text'" :value="objectInputValue(name, childName)" :placeholder="childSchema.description || t('agent.enterValue')" :disabled="disabled" @input="updateObjectField(name, childName, childSchema, $event)">
            </label>
          </div>
          <textarea
            v-else-if="schema.type === 'object'"
            class="cx-textarea mono flow-object-input"
            rows="3"
            :value="displayInputValue(name, schema)"
            :placeholder="t('agent.objectInputPlaceholder')"
            :disabled="disabled"
            @change="updateObjectInput(name, $event)"
          />
          <div v-else-if="schema.type === 'array' && schema.items?.type === 'object' && schema.items.properties" class="flow-list-builder">
            <div v-for="(item, itemIndex) in arrayObjectItems(name)" :key="itemIndex" class="flow-list-item">
              <div class="flow-list-item__head"><strong>{{ t('agent.itemNumber', { n: itemIndex + 1 }) }}</strong><button class="cx-iconbtn cx-iconbtn--sm" :disabled="disabled" @click="removeArrayObjectItem(name, itemIndex)"><i class="mdi mdi-delete-outline" /></button></div>
              <div v-if="rowParts(schema).first || rowParts(schema).booleans.length" class="flow-list-item__inline">
                <label v-if="rowParts(schema).first" class="flow-list-item__grow">
                  <span>{{ rowParts(schema).first![1].title || humanizeWorkflowField(rowParts(schema).first![0]) }}</span>
                  <input
                    class="cx-input"
                    :type="rowParts(schema).first![1].type === 'number' || rowParts(schema).first![1].type === 'integer' ? 'number' : 'text'"
                    :value="item[rowParts(schema).first![0]] ?? ''"
                    :list="rowOptions(rowParts(schema).first![1]['x-fengyu-options-from'], item.sheetName).length ? `dl-${node.id}-${name}-${rowParts(schema).first![0]}` : undefined"
                    :disabled="disabled"
                    @input="updateArrayObjectField(name, itemIndex, rowParts(schema).first![0], rowParts(schema).first![1], $event)"
                  >
                  <datalist v-if="rowOptions(rowParts(schema).first![1]['x-fengyu-options-from'], item.sheetName).length" :id="`dl-${node.id}-${name}-${rowParts(schema).first![0]}`">
                    <option v-for="option in rowOptions(rowParts(schema).first![1]['x-fengyu-options-from'], item.sheetName)" :key="option" :value="option" />
                  </datalist>
                </label>
                <label
                  v-for="([childName, childSchema]) in rowParts(schema).booleans"
                  :key="childName"
                  class="flow-switch"
                  :title="childSchema.description"
                >
                  <input type="checkbox" :checked="Boolean(item[childName])" :disabled="disabled" @change="updateArrayObjectField(name, itemIndex, childName, childSchema, $event)">
                  <span class="flow-switch__track" />
                  <span class="flow-switch__label">{{ childSchema.title || humanizeWorkflowField(childName) }}</span>
                </label>
              </div>
              <label v-for="([childName, childSchema]) in rowParts(schema).rest" :key="childName">
                <span>{{ childSchema.title || humanizeWorkflowField(childName) }}</span>
                <input
                  class="cx-input"
                  :type="childSchema.type === 'number' || childSchema.type === 'integer' ? 'number' : 'text'"
                  :value="item[childName] ?? ''"
                  :list="rowOptions(childSchema['x-fengyu-options-from'], item.sheetName).length ? `dl-${node.id}-${name}-${childName}` : undefined"
                  :disabled="disabled"
                  @input="updateArrayObjectField(name, itemIndex, childName, childSchema, $event)"
                >
                <datalist v-if="rowOptions(childSchema['x-fengyu-options-from'], item.sheetName).length" :id="`dl-${node.id}-${name}-${childName}`">
                  <option v-for="option in rowOptions(childSchema['x-fengyu-options-from'], item.sheetName)" :key="option" :value="option" />
                </datalist>
              </label>
            </div>
            <button class="flow-add-item" :disabled="disabled" @click="addArrayObjectItem(name)"><i class="mdi mdi-plus" /> {{ t('agent.addItem') }}</button>
          </div>
          <textarea
            v-else-if="schema.type === 'array'"
            class="cx-textarea flow-array-input"
            rows="2"
            :value="displayInputValue(name, schema)"
            :placeholder="t('agent.arrayInputPlaceholder')"
            :disabled="disabled"
            @input="updateSimpleInput(name, schema, $event)"
          />
          <div v-else-if="schema['x-fengyu-analyze'] === 'excel'" class="flow-analyze-input">
            <input
              class="cx-input"
              :value="displayInputValue(name, schema)"
              :placeholder="schema.description || t('agent.enterValue')"
              :disabled="disabled"
              @input="updateSimpleInput(name, schema, $event)"
            >
            <button
              class="flow-analyze-button"
              :disabled="disabled || workbookAnalyzing || !displayInputValue(name, schema)"
              :title="t('flows.analyzeWorkbook')"
              @click="analyzeNodeWorkbook"
            ><span v-if="workbookAnalyzing" class="cx-spin" /><i v-else class="mdi mdi-table-search" /></button>
            <small v-if="workbookAnalysisError" class="flow-analyze-error">{{ workbookAnalysisError }}</small>
            <small v-else-if="Object.keys(workbookAnalysis).length" class="flow-analyze-done">
              <i class="mdi mdi-check-circle-outline" /> {{ Object.keys(workbookAnalysis).length }} sheet(s)
            </small>
          </div>
          <textarea
            v-else-if="schema['x-fengyu-multiline']"
            class="cx-textarea flow-array-input"
            rows="3"
            :value="String(displayInputValue(name, schema) ?? '')"
            :placeholder="schema.description || t('agent.enterValue')"
            :disabled="disabled"
            @input="updateSimpleInput(name, schema, $event)"
          />
          <input
            v-else
            class="cx-input"
            :type="schema.type === 'integer' || schema.type === 'number' ? 'number' : 'text'"
            :value="displayInputValue(name, schema)"
            :placeholder="schema.description || t('agent.enterValue')"
            :disabled="disabled"
            @input="updateSimpleInput(name, schema, $event)"
          >
        </template>
        <div v-else class="flow-linked-input">
          <i class="mdi" :class="inputSource(name).startsWith('input::') ? 'mdi-form-textbox' : 'mdi-link-variant'" />
          <span>{{ inputSource(name).startsWith('input::') ? t('agent.usesWorkflowInput') : t('agent.usesNodeOutput') }}</span>
          <button class="flow-clear-link" @click="removeNodeArgument(name)">{{ t('agent.change') }}</button>
        </div>
      </div>
    </section>

    <section class="flow-config-section">
      <h3><i class="mdi mdi-logout-variant" /> {{ t('agent.outputConfig') }}</h3>
      <div class="flow-output-card">
        <strong>{{ t('agent.nodeResult') }}</strong>
        <div v-if="outputFields.length" class="flow-output-fields">
          <span v-for="([name, schema]) in outputFields" :key="name">
            <i class="mdi mdi-circle-medium" /><strong>{{ schema.title || humanizeWorkflowField(name) }}</strong><small>{{ t(`agent.fieldType.${schema.type || 'string'}`) }}{{ schema.description ? ` · ${schema.description}` : '' }}</small>
          </span>
        </div>
        <span v-if="downstreamNodes.length">
          {{ t('agent.outputUsedBy', { count: downstreamNodes.length }) }}
        </span>
        <span v-else>{{ t('agent.outputConnectHint') }}</span>
      </div>
    </section>

    <details class="flow-advanced">
      <summary>{{ t('agent.advancedSettings') }}</summary>
      <div class="flow-advanced__body">
        <label class="flow-field">
          <span>{{ t('agent.description') }}</span>
          <input v-model="node.data.description" class="cx-input" :disabled="disabled">
        </label>
        <label class="flow-field">
          <span>{{ t('agent.argumentsJson') }}</span>
          <textarea
            v-model="node.data.argsText"
            class="cx-textarea mono"
            rows="8"
            spellcheck="false"
            :disabled="disabled"
          />
        </label>
        <div v-if="advancedInputFields.length" class="flow-advanced-inputs">
          <label v-for="([name, schema]) in advancedInputFields" :key="name" class="flow-field">
            <span>{{ schema.title || humanizeWorkflowField(name) }}</span>
            <select
              v-if="schema.enum?.length"
              class="cx-input"
              :value="arguments_[name] ?? ''"
              :disabled="disabled"
              @change="updateSimpleInput(name, schema, $event)"
            >
              <option v-if="!requiredInputs.has(name)" value="">{{ t('agent.notSet') }}</option>
              <option v-for="option in schema.enum" :key="String(option)" :value="option">{{ option }}</option>
            </select>
            <label v-else-if="schema.type === 'boolean'" class="flow-boolean-input">
              <input type="checkbox" :checked="Boolean(arguments_[name])" :disabled="disabled" @change="updateSimpleInput(name, schema, $event)">
              <span>{{ t('agent.enabled') }}</span>
            </label>
            <textarea
              v-else-if="schema.type === 'array' || schema.type === 'object'"
              class="cx-textarea mono"
              rows="3"
              :value="displayInputValue(name, schema)"
              :placeholder="schema.type === 'array' ? t('agent.arrayInputPlaceholder') : t('agent.objectInputPlaceholder')"
              :disabled="disabled"
              @change="updateSimpleInput(name, schema, $event)"
            />
            <input
              v-else
              class="cx-input"
              :type="schema.type === 'integer' || schema.type === 'number' ? 'number' : 'text'"
              :value="displayInputValue(name, schema)"
              :placeholder="schema.description || t('agent.enterValue')"
              :disabled="disabled"
              @input="updateSimpleInput(name, schema, $event)"
            >
          </label>
        </div>
      </div>
    </details>
    <label class="flow-checkbox">
      <input v-model="node.data.requiresApproval" type="checkbox" :disabled="disabled">
      <span>{{ t('agent.requiresApproval') }}</span>
    </label>
  </div>
</template>

<style scoped>
.flow-inspector {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.flow-inspector__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
}

.flow-inspector__intro {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 16px;
  padding: 9px;
  color: rgba(var(--v-theme-on-surface), .72);
  font-size: 11px;
  line-height: 1.5;
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), .08);
}

.flow-inspector__intro i { flex: 0 0 auto; color: rgb(var(--v-theme-primary)); font-size: 15px; }
.flow-inspector .cx-alert { margin-bottom: 14px; font-size: 11px; }

.flow-config-section { margin-bottom: 18px; }
.flow-config-section h3 { display: flex; gap: 6px; align-items: center; margin: 0 0 9px; font-size: 12px; }
.flow-config-section h3 i { color: rgb(var(--v-theme-primary)); font-size: 15px; }

.flow-config-section__heading { display: flex; gap: 8px; align-items: center; justify-content: space-between; margin-bottom: 9px; }
.flow-config-section__heading h3 { margin: 0; }
.flow-completion { display: inline-flex; gap: 3px; align-items: center; padding: 3px 7px; font-size: 9px; font-weight: 650; border-radius: 10px; }
.flow-completion--ready { color: rgb(var(--v-theme-success)); background: rgba(var(--v-theme-success), .12); }
.flow-completion--warn { color: rgb(var(--v-theme-warning)); background: rgba(var(--v-theme-warning), .14); }

.flow-config-empty {
  padding: 10px;
  font-size: 11px;
  border: 1px dashed rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.flow-argument {
  margin-bottom: 11px;
  padding: 9px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.flow-argument__label {
  display: flex;
  gap: 7px;
  align-items: center;
  margin-bottom: 5px;
  font-size: 11px;
  font-weight: 650;
}

.flow-argument > small {
  display: block;
  margin: -1px 0 7px;
  color: rgba(var(--v-theme-on-surface), .6);
  font-size: 10px;
  line-height: 1.4;
}

.flow-required {
  padding: 1px 5px;
  color: rgb(var(--v-theme-primary));
  font-size: 9px;
  font-weight: 600;
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), .12);
}

.flow-type-chip { margin-left: auto; color: rgba(var(--v-theme-on-surface), .5); font-size: 9px; font-weight: 500; }
.flow-source-select { margin-bottom: 7px; }
.flow-argument .cx-input,
.flow-argument .cx-textarea { width: 100%; font-size: 11px; }

.flow-boolean-input { display: flex; gap: 7px; align-items: center; min-height: 30px; font-size: 11px; }
.flow-array-input,
.flow-object-input { resize: vertical; }

.flow-linked-input {
  display: flex;
  gap: 6px;
  align-items: center;
  min-height: 30px;
  padding: 6px 8px;
  color: rgb(var(--v-theme-primary));
  font-size: 10px;
  border-radius: 6px;
  background: rgba(var(--v-theme-primary), .08);
}
.flow-linked-input span { flex: 1; }
.flow-clear-link { padding: 2px 5px; color: inherit; font: inherit; font-size: 9px; border: 0; border-radius: 5px; background: rgba(var(--v-theme-primary), .1); cursor: pointer; }

.flow-nested-fields,
.flow-list-builder { display: flex; flex-direction: column; gap: 8px; }

.flow-analyze-input { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.flow-analyze-input .cx-input { flex: 1; min-width: 0; }
.flow-analyze-button {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  padding: 4px 9px;
  color: rgb(var(--v-theme-primary));
  font: inherit;
  font-size: 10px;
  border: 1px dashed rgba(var(--v-theme-primary), 0.6);
  border-radius: 7px;
  background: rgba(var(--v-theme-primary), 0.05);
  cursor: pointer;
}
.flow-analyze-button:disabled { opacity: 0.45; cursor: not-allowed; }
.flow-analyze-error { width: 100%; color: rgb(var(--v-theme-error)); font-size: 10px; }
.flow-analyze-done { display: inline-flex; align-items: center; gap: 4px; color: rgb(var(--v-theme-success)); font-size: 10px; }
.flow-nested-fields label,
.flow-list-item label { display: flex; flex-direction: column; gap: 4px; color: rgba(var(--v-theme-on-surface), .64); font-size: 9px; }
.flow-list-item { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 9px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 8px; background: rgb(var(--v-theme-surface-container)); }
.flow-list-item__head { display: flex; grid-column: 1 / -1; align-items: center; justify-content: space-between; font-size: 10px; }

.flow-list-item__inline { display: flex; grid-column: 1 / -1; gap: 10px; align-items: flex-end; }
.flow-list-item__grow { flex: 1; min-width: 0; }
.flow-list-item__inline > label { display: flex; flex-direction: column; gap: 4px; }

/* boolean rows render as a compact switch instead of a bare checkbox */
.flow-switch { display: inline-flex; gap: 7px; align-items: center; min-height: 32px; padding-bottom: 2px; color: rgba(var(--v-theme-on-surface), 0.8); font-size: 10px; cursor: pointer; user-select: none; }
.flow-switch input { position: absolute; opacity: 0; width: 0; height: 0; }
.flow-switch__track { position: relative; flex: 0 0 auto; width: 30px; height: 16px; border-radius: 8px; background: rgba(var(--v-theme-on-surface), 0.25); transition: background 0.15s ease; }
.flow-switch__track::after { content: ''; position: absolute; top: 2px; left: 2px; width: 12px; height: 12px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3); transition: transform 0.15s ease; }
.flow-switch input:checked + .flow-switch__track { background: rgb(var(--v-theme-primary)); }
.flow-switch input:checked + .flow-switch__track::after { transform: translateX(14px); }
.flow-switch input:disabled + .flow-switch__track { opacity: 0.45; }
.flow-switch:has(input:focus-visible) .flow-switch__track { outline: 2px solid rgb(var(--v-theme-primary)); outline-offset: 2px; }
.flow-add-item { display: inline-flex; gap: 5px; align-items: center; justify-content: center; padding: 6px 8px; color: rgb(var(--v-theme-primary)); font: inherit; font-size: 10px; border: 1px dashed rgba(var(--v-theme-primary), .6); border-radius: 7px; background: rgba(var(--v-theme-primary), .05); cursor: pointer; }

.flow-output-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px;
  font-size: 11px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}
.flow-output-card span { color: rgba(var(--v-theme-on-surface), .62); font-size: 10px; line-height: 1.4; }
.flow-output-fields { display: flex; flex-direction: column; gap: 3px; padding: 5px 0; }
.flow-output-fields span { display: grid; grid-template-columns: auto auto 1fr; gap: 3px; align-items: center; padding: 5px 0; }
.flow-output-fields i { color: rgb(var(--v-theme-primary)); }
.flow-output-fields strong { color: rgb(var(--v-theme-on-surface)); font-size: 10px; }
.flow-output-fields small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.flow-advanced { margin-bottom: 14px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.flow-advanced summary { padding: 10px 0; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; cursor: pointer; }
.flow-advanced__body { padding-top: 3px; }

.flow-field { display: block; margin-bottom: 14px; }
.flow-field > span { display: block; margin-bottom: 6px; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; }
.flow-field .cx-textarea { width: 100%; resize: vertical; font-size: 11px; }

.flow-checkbox { display: flex; gap: 8px; align-items: center; margin-bottom: 14px; font-size: 12px; }
</style>
