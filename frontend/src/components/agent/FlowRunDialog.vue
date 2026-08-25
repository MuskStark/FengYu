<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { makeDesktop } from '@/mf/desktop'
import { fetchCatalogOptions } from '@/components/agent/optionSource'
import { createRunFileGrantLedger } from '@/components/agent/runFileGrantLedger'
import {
  workbookOptionsFromAnalysis,
  type ExcelWorkbookAnalysisResponse,
} from '@/components/agent/workbookAnalysis'
import type {
  ActiveFileEntry,
  AgentRunFile,
  AiPermissionMode,
} from '@/api/types'
import {
  humanizeWorkflowField,
  isWorkflowValueConfigured,
  parseWorkflowArguments,
  parseWorkflowSchema,
  type WorkflowEnumSource,
  type WorkflowSchemaProperty,
} from '@/components/agent/workflow'

/**
 * Flowise-style run dialog: renders the workflow's input schema as a friendly
 * form (file pickers, plugin-sourced dropdowns, Excel-analyzed candidates),
 * then hands the bound inputs + file grants to the parent to start the run.
 */
const props = defineProps<{
  open: boolean
  workflowTitle: string
  nodeCount: number
  inputSchemaText: string
  busy?: boolean
  canCreateWebhook?: boolean
}>()
const inputsText = defineModel<string>('inputsText', { required: true })
const emit = defineEmits<{
  close: []
  run: [payload: { inputs: Record<string, unknown>; permissionMode: AiPermissionMode; files: AgentRunFile[] }]
  'create-webhook': [payload: { inputs: Record<string, unknown>; permissionMode: AiPermissionMode }]
}>()

const { t } = useI18n()
const permissionMode = ref<AiPermissionMode>('ask-for-approval')

const schema = computed(() => parseWorkflowSchema(props.inputSchemaText))
const schemaFields = computed(() => Object.entries(schema.value.properties ?? {}))
const requiredInputs = computed(() => new Set(schema.value.required ?? []))
const runInputs = computed(() => parseWorkflowArguments(inputsText.value) ?? {})

// ── run-form file inputs + dynamic option sources ───────────────────────
/** Per-input grants from the run dialog's file pickers (POST /api/ai/files/upload). */
const runFileRefs = ref<Record<string, ActiveFileEntry[]>>({})
/**
 * Grant ownership ledger: the dialog revokes what it still owns (replace/clear/close/unmount,
 * late picker completions); once a run is created from the refs the run's terminal cleanup owns
 * the revoke, so the dialog never touches them again. runFileRefs above is only the view mirror.
 */
const grantLedger = createRunFileGrantLedger((item) => {
  void api.revokeAiFile(item.pluginId, item.ref.id).catch(() => {/* best effort */})
})
/** Display names of the picked files, so the form can show what was chosen. */
const runFileNames = ref<Record<string, string>>({})
/** Options fetched from plugin list tools for `x-fengyu-enum` inputs. */
const enumOptions = ref<Record<string, Array<{ value: unknown; label: string }>>>({})
const enumLoading = ref<Record<string, boolean>>({})
const enumError = ref<Record<string, string | null>>({})
const runFileError = ref<Record<string, string | null>>({})
/** Sheet → header columns of the picked workbook, filled by the automatic analyze RPC. */
const workbookAnalysis = ref<Record<string, string[]>>({})
const workbookAnalyzing = ref(false)
const workbookAnalysisError = ref<string | null>(null)
let workbookAnalysisRequest = 0

/** Inputs whose value is a host-managed shared scratch directory (no user interaction). */
const autoSharedDirInputs = computed(() => schemaFields.value
  .filter(([, property]) => property['x-fengyu-auto'] === 'shared-directory')
  .map(([name]) => name))

const missingRunInputs = computed(() =>
  (schema.value.required ?? []).filter((name) => !isWorkflowValueConfigured(runInputs.value[name])))
const missingRunInputLabels = computed(() => {
  const properties = schema.value.properties ?? {}
  return missingRunInputs.value.map((name) => properties[name]?.title || humanizeWorkflowField(name))
})
const webhookHasEphemeralInputs = computed(() => schemaFields.value.some(([, property]) =>
  property.format === 'fengyu-file' || property.format === 'fengyu-directory'
    || property['x-fengyu-auto'] === 'shared-directory'))

function resetDialogState() {
  workbookAnalysisRequest += 1
  grantLedger.beginSession()
  runFileRefs.value = {}
  runFileNames.value = {}
  enumOptions.value = {}
  enumLoading.value = {}
  enumError.value = {}
  runFileError.value = {}
  workbookAnalysis.value = {}
  workbookAnalyzing.value = false
  workbookAnalysisError.value = null
}

function setInputRaw(name: string, value: unknown) {
  const next = { ...runInputs.value, [name]: value }
  inputsText.value = JSON.stringify(next, null, 2)
}

/** Re-mints a run-scoped grant for a native path explicitly saved as this input's default. */
async function grantDefaultNativePath(name: string, property: WorkflowSchemaProperty, path: string) {
  const kind = property.format === 'fengyu-directory' ? 'directory' : 'file'
  const writable = kind === 'directory' && property['x-fengyu-file-access'] === 'read-write'
  const session = grantLedger.session()
  runFileError.value = { ...runFileError.value, [name]: null }
  try {
    const refs = await api.grantAiNativePath(path, kind, writable)
    if (!grantLedger.accept(session, name, refs)) return
    runFileRefs.value = { ...runFileRefs.value, [name]: refs }
    runFileNames.value = {
      ...runFileNames.value,
      [name]: path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || path,
    }
    setInputRaw(name, `@file:${name}`)
    if (kind === 'file' && property['x-fengyu-analyze'] === 'excel') void analyzeWorkbook(refs)
  } catch (e) {
    runFileError.value = {
      ...runFileError.value,
      [name]: e instanceof Error ? e.message : t('agent.failed'),
    }
  }
}

/** Seeds defaults and shared-dir placeholders, and starts dynamic option loads. */
function prepare() {
  for (const [name, property] of schemaFields.value) {
    if (property['x-fengyu-auto'] === 'shared-directory') {
      setInputRaw(name, `@file:${name}`)
      continue
    }
    // Grants are run-scoped. Never reuse a persisted native path or a placeholder
    // from a previous dialog opening without minting a fresh authorization.
    if (property.format === 'fengyu-file' || property.format === 'fengyu-directory') {
      setInputRaw(name, '')
      if (typeof property.default === 'string' && property.default.trim() && makeDesktop()) {
        void grantDefaultNativePath(name, property, property.default)
      }
      continue
    }
    if ('default' in property && property.default !== undefined
      && !isWorkflowValueConfigured(runInputs.value[name])) {
      setInputRaw(name, property.default)
    }
    const source = property['x-fengyu-enum']
    if (source) void loadEnumOptions(name, source)
  }
}

watch(() => props.open, (open) => {
  if (open) {
    resetDialogState()
    prepare()
  } else {
    // Closing without submitting: the still-owned grants are revoked here, not on the next
    // open (which would race a fresh picker session already minting replacements).
    grantLedger.releaseRemaining()
  }
})

onBeforeUnmount(() => {
  grantLedger.releaseRemaining()
})

async function pickRunFile(name: string, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const session = grantLedger.session()
  runFileError.value = { ...runFileError.value, [name]: null }
  try {
    const refs = await api.uploadAiFile(file)
    if (!grantLedger.accept(session, name, refs)) return
    runFileRefs.value = { ...runFileRefs.value, [name]: refs }
    runFileNames.value = { ...runFileNames.value, [name]: file.name }
    setInputRaw(name, `@file:${name}`)
    if (schema.value.properties?.[name]?.['x-fengyu-analyze'] === 'excel') {
      void analyzeWorkbook(refs)
    }
  } catch (e) {
    runFileError.value = { ...runFileError.value, [name]: e instanceof Error ? e.message : t('agent.failed') }
  }
}

async function storeRunDirectory(name: string, displayName: string, refsPromise: Promise<ActiveFileEntry[]>) {
  const session = grantLedger.session()
  runFileError.value = { ...runFileError.value, [name]: null }
  try {
    const refs = await refsPromise
    if (!refs.length) throw new Error(t('aichat.fileNeedsPlugin'))
    if (!grantLedger.accept(session, name, refs)) return
    runFileRefs.value = { ...runFileRefs.value, [name]: refs }
    runFileNames.value = { ...runFileNames.value, [name]: displayName }
    setInputRaw(name, `@file:${name}`)
  } catch (e) {
    runFileError.value = {
      ...runFileError.value,
      [name]: e instanceof Error ? e.message : t('aichat.attachDirectoryFailed'),
    }
  }
}

/** Directory inputs are granted at run time; a persisted native path is never authority. */
async function pickRunDirectory(name: string, writable: boolean) {
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickDirectory()
    if (!path) return
    const displayName = path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || path
    await storeRunDirectory(name, displayName, api.grantAiNativePath(path, 'directory', writable))
    return
  }

  const input = document.createElement('input')
  input.type = 'file'
  input.setAttribute('webkitdirectory', '')
  input.multiple = true
  input.onchange = () => {
    const files = input.files ? Array.from(input.files) : []
    if (!files.length) return
    const displayName = files[0].webkitRelativePath.split('/')[0] || files[0].name
    void storeRunDirectory(name, displayName, api.uploadAiDirectory(files, writable))
  }
  input.click()
}

/**
 * Analyzes the just-picked workbook through the Excel plugin's own copy of the grant, so
 * the run form's sheet/column fields become datalist candidates instead of blind typing.
 * Failures never block the run — the fields stay free-text.
 */
async function analyzeWorkbook(refs: ActiveFileEntry[]) {
  const requestId = ++workbookAnalysisRequest
  workbookAnalyzing.value = true
  workbookAnalysis.value = {}
  workbookAnalysisError.value = null
  const excelRef = refs.find((entry) => entry.pluginId === 'fan.summer.excel')
  if (!excelRef) {
    workbookAnalyzing.value = false
    return
  }
  try {
    // `excel_analyze` is the concise AI contract and returns sheet names only. The UI
    // `analyze` RPC returns the full sheet/column structure this configuration form needs.
    // Keep one bounded preview session: the real run re-analyzes under its own run-scoped
    // session before executing, so preview state is never a correctness dependency.
    const result = await api.invokePluginMethod<ExcelWorkbookAnalysisResponse>(
      'fan.summer.excel',
      'analyze',
      { session: 'flow-run-form-preview', sourceFile: excelRef.ref },
    )
    if (requestId === workbookAnalysisRequest) {
      workbookAnalysis.value = workbookOptionsFromAnalysis(result)
    }
  } catch (e) {
    if (requestId !== workbookAnalysisRequest) return
    workbookAnalysis.value = {}
    workbookAnalysisError.value = e instanceof Error ? e.message : t('agent.failed')
  } finally {
    if (requestId === workbookAnalysisRequest) workbookAnalyzing.value = false
  }
}

/** Option candidates for a field annotated with `x-fengyu-options-from`. */
function optionsFromSource(source: string | undefined, rowSheet?: unknown): string[] {
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

function clearRunFile(name: string) {
  grantLedger.clear(name)
  const nextRefs = { ...runFileRefs.value }
  delete nextRefs[name]
  runFileRefs.value = nextRefs
  const nextNames = { ...runFileNames.value }
  delete nextNames[name]
  runFileNames.value = nextNames
  setInputRaw(name, '')
  if (schema.value.properties?.[name]?.['x-fengyu-analyze'] === 'excel') {
    workbookAnalysisRequest += 1
    workbookAnalysis.value = {}
    workbookAnalyzing.value = false
    workbookAnalysisError.value = null
  }
}

async function loadEnumOptions(name: string, source: WorkflowEnumSource) {
  if (enumLoading.value[name] || enumOptions.value[name]) return
  enumLoading.value = { ...enumLoading.value, [name]: true }
  enumError.value = { ...enumError.value, [name]: null }
  try {
    // Same catalog fetch the node inspector uses (unified option-source standard):
    // x-fengyu-enum annotations and flowNodes `source` declarations share one path.
    enumOptions.value = {
      ...enumOptions.value,
      [name]: await fetchCatalogOptions(source.plugin, source),
    }
  } catch (e) {
    enumError.value = { ...enumError.value, [name]: e instanceof Error ? e.message : t('agent.failed') }
  } finally {
    enumLoading.value = { ...enumLoading.value, [name]: false }
  }
}

function enumOptionSelected(name: string, value: unknown): boolean {
  const current = runInputs.value[name]
  return Array.isArray(current) ? current.some((item) => String(item) === String(value))
    : String(current) === String(value)
}

function toggleEnumOption(name: string, value: unknown) {
  const current = runInputs.value[name]
  const list = Array.isArray(current) ? current : []
  const next = enumOptionSelected(name, value)
    ? list.filter((item) => String(item) !== String(value))
    : [...list, value]
  setInputRaw(name, next)
}

function selectEnumOption(name: string, event: Event) {
  const value = (event.target as HTMLSelectElement).value
  const option = enumOptions.value[name]?.find((item) => String(item.value) === value)
  setInputRaw(name, option ? option.value : value)
}

// ── run-form array-of-object row editor (schema.items.properties driven) ──

function runArrayRows(name: string): Record<string, unknown>[] {
  const value = runInputs.value[name]
  return Array.isArray(value)
    ? value.filter((row) => row && typeof row === 'object' && !Array.isArray(row)) as Record<string, unknown>[]
    : []
}

function writeRunArrayRows(name: string, rows: Record<string, unknown>[]) {
  setInputRaw(name, rows)
}

function updateRunArrayField(name: string, index: number, field: string, value: unknown) {
  const rows = runArrayRows(name).map((row) => ({ ...row }))
  if (!rows[index]) return
  if (value === undefined || value === '') delete rows[index][field]
  else rows[index] = { ...rows[index], [field]: value }
  writeRunArrayRows(name, rows)
}

function addRunArrayRow(name: string) {
  writeRunArrayRows(name, [...runArrayRows(name), {}])
}

function removeRunArrayRow(name: string, index: number) {
  writeRunArrayRows(name, runArrayRows(name).filter((_, i) => i !== index))
}

function updateRunArrayFieldFromEvent(
  name: string,
  index: number,
  field: string,
  property: WorkflowSchemaProperty,
  event: Event,
) {
  const input = event.target as HTMLInputElement
  const value = property.type === 'boolean'
    ? input.checked
    : (property.type === 'integer' || property.type === 'number')
        ? (input.value === '' ? undefined : Number(input.value))
        : input.value
  updateRunArrayField(name, index, field, value)
}

/** Mirrors the node row editor: first ordinary field + switches, then remaining fields. */
function runRowParts(property: WorkflowSchemaProperty): {
  first: [string, WorkflowSchemaProperty] | null
  rest: Array<[string, WorkflowSchemaProperty]>
  booleans: Array<[string, WorkflowSchemaProperty]>
} {
  const entries = Object.entries(property.items?.properties ?? {})
    .filter(([, child]) => !child['x-fengyu-advanced'])
  const booleans = entries.filter(([, child]) => child.type === 'boolean')
  const others = entries.filter(([, child]) => child.type !== 'boolean')
  return { first: others[0] ?? null, rest: others.slice(1), booleans }
}

// ── simple typed input editors ───────────────────────────────────────────

interface InputSchema {
  type?: string
  enum?: unknown[]
  items?: InputSchema
}

function workflowInputValue(name: string): unknown {
  return runInputs.value[name] ?? ''
}

function displayWorkflowInputValue(name: string, property: WorkflowSchemaProperty): string | number {
  const value = workflowInputValue(name)
  if (property.type === 'array') return Array.isArray(value) ? value.join(', ') : String(value ?? '')
  if (property.type === 'object') return JSON.stringify(value || {}, null, 2)
  return typeof value === 'number' ? value : String(value ?? '')
}

function valueFromInput(property: InputSchema, event: Event): unknown {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  if (property.type === 'boolean' && target instanceof HTMLInputElement) return target.checked
  if (property.type === 'integer' || property.type === 'number') {
    // Number('') === 0: clearing the field removes the key instead of writing a 0.
    if (target.value === '') return undefined
    const parsed = Number(target.value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (property.enum?.length) return property.enum.find((option) => String(option) === target.value) ?? target.value
  return target.value
}

function setWorkflowInputValue(name: string, property: WorkflowSchemaProperty, event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  let value: unknown
  if (property.type === 'array') {
    const items = target.value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean)
    value = property.items?.type === 'integer' || property.items?.type === 'number' ? items.map(Number) : items
  } else if (property.type === 'object') {
    try {
      value = JSON.parse(target.value || '{}')
    } catch {
      return
    }
  } else {
    value = valueFromInput(property, event)
  }
  setInputRaw(name, value)
}

function startRun() {
  // File-class inputs: picked-file grants travel with the run; auto shared dirs are
  // minted server-side. Args already carry the matching @file:<name> placeholders.
  const files: AgentRunFile[] = Object.entries(runFileRefs.value).map(([name, refs]) => ({ name, refs }))
  for (const name of autoSharedDirInputs.value) {
    files.push({ name, createSharedDirectory: true })
  }
  // Ownership leaves the dialog with this submit: the created run's terminal cleanup (or the
  // start-call's failure paths, see FlowBuilder.startRun) now owns every revoke.
  grantLedger.markTransferred()
  emit('run', {
    inputs: parseWorkflowArguments(inputsText.value) ?? {},
    permissionMode: permissionMode.value,
    files,
  })
}

function createWebhook() {
  emit('create-webhook', {
    inputs: parseWorkflowArguments(inputsText.value) ?? {},
    permissionMode: permissionMode.value,
  })
}
</script>

<template>
  <div v-if="open" class="flow-run-backdrop" @click.self="emit('close')">
    <section class="flow-run-dialog" role="dialog" aria-modal="true">
      <div class="flow-run-dialog__icon"><i class="mdi mdi-play" /></div>
      <div class="flow-run-dialog__heading">
        <h2>{{ t('agent.testRun') }}</h2>
        <p>{{ workflowTitle }} · {{ nodeCount }} {{ t('agent.nodes') }}</p>
      </div>
      <button class="cx-iconbtn cx-iconbtn--sm flow-run-dialog__close" :aria-label="t('flows.close')" @click="emit('close')"><i class="mdi mdi-close" /></button>

      <div v-if="schemaFields.length" class="flow-run-form">
        <label v-for="([name, property]) in schemaFields" :key="name" class="flow-field">
          <span>{{ property.title || humanizeWorkflowField(name) }} <em v-if="requiredInputs.has(name)">*</em></span>
          <small v-if="property.description">{{ property.description }}</small>

          <span v-if="property.format === 'fengyu-file'" class="flow-file-input">
            <label
              :for="`run-file-${name}`"
              class="cx-btn cx-btn--outline flow-file-label"
              :class="{ 'flow-file-label--disabled': busy }"
            ><i class="mdi mdi-paperclip" /> {{ runFileNames[name] || t('agent.chooseFile') }}</label>
            <input
              :id="`run-file-${name}`"
              type="file"
              class="flow-file-native"
              :disabled="busy"
              @change="pickRunFile(name, $event)"
            >
            <button
              v-if="runFileRefs[name]"
              class="cx-iconbtn cx-iconbtn--sm"
              :title="t('agent.clearFile')"
              :disabled="busy"
              @click.prevent="clearRunFile(name)"
            ><i class="mdi mdi-close" /></button>
            <small v-if="runFileRefs[name]" class="flow-file-chosen">
              <i class="mdi mdi-check-circle-outline" /> {{ runFileNames[name] }}
            </small>
            <small v-if="workbookAnalyzing && property['x-fengyu-analyze'] === 'excel'" class="cx-muted flow-file-chosen">
              <span class="cx-spin" /> {{ t('agent.analyzingWorkbook') }}
            </small>
            <small v-if="workbookAnalysisError && property['x-fengyu-analyze'] === 'excel'" class="flow-file-error">
              {{ t('agent.analyzeFailed') }}: {{ workbookAnalysisError }}
            </small>
            <small v-if="runFileError[name]" class="flow-file-error">{{ runFileError[name] }}</small>
          </span>

          <span v-else-if="property['x-fengyu-auto'] === 'shared-directory'" class="flow-auto-dir">
            <i class="mdi mdi-folder-sync-outline" />
            <span>{{ t('agent.autoSharedDir') }}</span>
          </span>

          <span v-else-if="property.format === 'fengyu-directory'" class="flow-file-input">
            <button
              type="button"
              class="cx-btn cx-btn--outline flow-file-label"
              :disabled="busy"
              @click.prevent="pickRunDirectory(name, property['x-fengyu-file-access'] === 'read-write')"
            ><i class="mdi mdi-folder-outline" /> {{ runFileNames[name] || t('agent.chooseDirectory') }}</button>
            <button
              v-if="runFileRefs[name]"
              class="cx-iconbtn cx-iconbtn--sm"
              :title="t('agent.clearFile')"
              :disabled="busy"
              @click.prevent="clearRunFile(name)"
            ><i class="mdi mdi-close" /></button>
            <small v-if="runFileRefs[name]" class="flow-file-chosen">
              <i class="mdi mdi-check-circle-outline" /> {{ runFileNames[name] }}
            </small>
            <small v-if="runFileError[name]" class="flow-file-error">{{ runFileError[name] }}</small>
          </span>

          <template v-else-if="property['x-fengyu-enum']">
            <span v-if="enumLoading[name]" class="cx-muted flow-enum-status">
              <span class="cx-spin" /> {{ t('agent.loadingOptions') }}
            </span>
            <template v-else-if="enumOptions[name]?.length">
              <select
                v-if="!property['x-fengyu-enum'].multiple"
                class="cx-input"
                :value="String(workflowInputValue(name) ?? '')"
                :disabled="busy"
                @change="selectEnumOption(name, $event)"
              >
                <option value="">{{ t('agent.notSet') }}</option>
                <option
                  v-for="option in enumOptions[name]"
                  :key="String(option.value)"
                  :value="String(option.value)"
                >{{ option.label || String(option.value) }}</option>
              </select>
              <div v-else class="flow-enum-list">
                <label v-for="option in enumOptions[name]" :key="String(option.value)">
                  <input
                    type="checkbox"
                    :checked="enumOptionSelected(name, option.value)"
                    :disabled="busy"
                    @change="toggleEnumOption(name, option.value)"
                  >
                  <span>{{ option.label || String(option.value) }}</span>
                </label>
              </div>
            </template>
            <template v-else>
              <small v-if="enumError[name]" class="flow-file-error">{{ enumError[name] }}</small>
              <small v-else-if="property['x-fengyu-enum']?.plugin === 'fan.summer.email'" class="cx-muted">
                {{ t('agent.enumEmailHint') }}
              </small>
              <small v-else class="cx-muted">{{ t('agent.noOptions') }}</small>
              <input
                class="cx-input"
                type="text"
                :value="displayWorkflowInputValue(name, property)"
                :disabled="busy"
                :placeholder="t('agent.enterValue')"
                @input="setWorkflowInputValue(name, property, $event)"
              >
            </template>
          </template>

          <div v-else-if="property.type === 'array' && property.items?.type === 'object' && property.items.properties" class="flow-rules-editor">
            <span v-if="workbookAnalyzing" class="cx-muted flow-enum-status">
              <span class="cx-spin" /> {{ t('agent.analyzingWorkbook') }}
            </span>
            <div v-for="(row, index) in runArrayRows(name)" :key="index" class="flow-rule-row">
              <div class="flow-rule-row__head">
                <strong>{{ t('agent.ruleRow', { n: index + 1 }) }}</strong>
                <button
                  class="cx-iconbtn cx-iconbtn--sm"
                  :title="t('agent.removeRule')"
                  :disabled="busy"
                  @click.prevent="removeRunArrayRow(name, index)"
                ><i class="mdi mdi-delete-outline" /></button>
              </div>
              <div v-if="runRowParts(property).first || runRowParts(property).booleans.length" class="flow-rule-row__inline">
                <label v-if="runRowParts(property).first" class="flow-rule-row__grow">
                  <span>{{ runRowParts(property).first![1].title || humanizeWorkflowField(runRowParts(property).first![0]) }}</span>
                  <input
                    class="cx-input"
                    :type="runRowParts(property).first![1].type === 'integer' || runRowParts(property).first![1].type === 'number' ? 'number' : 'text'"
                    :value="String(row[runRowParts(property).first![0]] ?? '')"
                    :list="optionsFromSource(runRowParts(property).first![1]['x-fengyu-options-from'], row.sheetName).length ? `dl-${name}-${runRowParts(property).first![0]}` : undefined"
                    :placeholder="runRowParts(property).first![1].description || t('agent.enterValue')"
                    :disabled="busy"
                    @input="updateRunArrayFieldFromEvent(name, index, runRowParts(property).first![0], runRowParts(property).first![1], $event)"
                  >
                  <datalist
                    v-if="optionsFromSource(runRowParts(property).first![1]['x-fengyu-options-from'], row.sheetName).length"
                    :id="`dl-${name}-${runRowParts(property).first![0]}`"
                  >
                    <option
                      v-for="option in optionsFromSource(runRowParts(property).first![1]['x-fengyu-options-from'], row.sheetName)"
                      :key="option"
                      :value="option"
                    />
                  </datalist>
                </label>
                <label
                  v-for="([fieldName, fieldSchema]) in runRowParts(property).booleans"
                  :key="fieldName"
                  class="flow-rule-switch"
                  :title="fieldSchema.description"
                >
                  <input
                    type="checkbox"
                    :checked="Boolean(row[fieldName])"
                    :disabled="busy"
                    @change="updateRunArrayFieldFromEvent(name, index, fieldName, fieldSchema, $event)"
                  >
                  <span>{{ fieldSchema.title || humanizeWorkflowField(fieldName) }}</span>
                </label>
              </div>
              <label v-for="([fieldName, fieldSchema]) in runRowParts(property).rest" :key="fieldName">
                <span>{{ fieldSchema.title || humanizeWorkflowField(fieldName) }}</span>
                <input
                  class="cx-input"
                  :type="fieldSchema.type === 'integer' || fieldSchema.type === 'number' ? 'number' : 'text'"
                  :value="String(row[fieldName] ?? '')"
                  :list="optionsFromSource(fieldSchema['x-fengyu-options-from'], row.sheetName).length ? `dl-${name}-${fieldName}` : undefined"
                  :placeholder="fieldSchema.description || t('agent.enterValue')"
                  :disabled="busy"
                  @input="updateRunArrayFieldFromEvent(name, index, fieldName, fieldSchema, $event)"
                >
                <datalist
                  v-if="optionsFromSource(fieldSchema['x-fengyu-options-from'], row.sheetName).length"
                  :id="`dl-${name}-${fieldName}`"
                >
                  <option
                    v-for="option in optionsFromSource(fieldSchema['x-fengyu-options-from'], row.sheetName)"
                    :key="option"
                    :value="option"
                  />
                </datalist>
              </label>
            </div>
            <button class="flow-add-item" :disabled="busy" @click.prevent="addRunArrayRow(name)">
              <i class="mdi mdi-plus" /> {{ t('agent.addRule') }}
            </button>
          </div>

          <label v-else-if="property.type === 'boolean'" class="flow-boolean-input"><input type="checkbox" :checked="Boolean(workflowInputValue(name))" :disabled="busy" @change="setWorkflowInputValue(name, property, $event)"><span>{{ t('agent.enabled') }}</span></label>
          <textarea v-else-if="property.type === 'array' || property.type === 'object'" class="cx-textarea" rows="3" :value="displayWorkflowInputValue(name, property)" :placeholder="property.type === 'array' ? t('agent.arrayInputPlaceholder') : t('agent.objectInputPlaceholder')" :disabled="busy" @change="setWorkflowInputValue(name, property, $event)" />
          <template v-else>
            <input
              class="cx-input"
              :type="property.type === 'integer' || property.type === 'number' ? 'number' : 'text'"
              :value="displayWorkflowInputValue(name, property)"
              :list="optionsFromSource(property['x-fengyu-options-from']).length ? `dl-${name}` : undefined"
              :placeholder="property.description || t('agent.enterValue')"
              :disabled="busy"
              @input="setWorkflowInputValue(name, property, $event)"
            >
            <datalist v-if="optionsFromSource(property['x-fengyu-options-from']).length" :id="`dl-${name}`">
              <option
                v-for="option in optionsFromSource(property['x-fengyu-options-from'])"
                :key="option"
                :value="option"
              />
            </datalist>
          </template>
        </label>
      </div>
      <div v-else class="cx-muted flow-config-empty">{{ t('agent.noRunInputs') }}</div>

      <label class="flow-field"><span>{{ t('agent.permissionMode') }}</span><select v-model="permissionMode" class="cx-select" :disabled="busy"><option value="ask-for-approval">{{ t('aichat.permissionAsk') }}</option><option value="approve-for-me">{{ t('aichat.permissionAuto') }}</option><option value="full-access">{{ t('aichat.permissionFullAccess') }}</option></select></label>
      <small class="cx-muted">{{ t('agent.runInputsHint') }}</small>
      <details class="flow-advanced"><summary>{{ t('agent.advancedJsonInput') }}</summary><div class="flow-advanced__body"><textarea v-model="inputsText" class="cx-textarea mono" rows="6" :disabled="busy" /></div></details>
      <div v-if="missingRunInputs.length" class="cx-alert cx-alert--error"><span class="cx-alert__body">{{ t('agent.missingRunInputs', { names: missingRunInputLabels.join(', ') }) }}</span></div>
      <div v-if="canCreateWebhook && webhookHasEphemeralInputs" class="cx-alert cx-alert--error"><span class="cx-alert__body">{{ t('agent.webhookEphemeralFiles') }}</span></div>
      <div class="flow-run-dialog__actions">
        <button class="cx-btn cx-btn--outline" @click="emit('close')">{{ t('common.cancel') }}</button>
        <button
          v-if="canCreateWebhook"
          class="cx-btn cx-btn--outline"
          :disabled="busy || !!missingRunInputs.length || webhookHasEphemeralInputs"
          @click="createWebhook"
        ><i class="mdi mdi-webhook" /> {{ t('agent.createWebhook') }}</button>
        <button class="flow-run-button" :disabled="busy || !!missingRunInputs.length" @click="startRun"><i class="mdi mdi-play" /> {{ t('agent.startRun') }}</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.flow-run-backdrop {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(0, 0, 0, .48);
  backdrop-filter: blur(3px);
}

.flow-run-dialog {
  position: relative;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 14px;
  width: min(560px, 100%);
  max-height: calc(100vh - 40px);
  padding: 22px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 14px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 24px 72px rgba(0, 0, 0, .35);
  overflow-y: auto;
}

.flow-run-dialog__icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  color: rgb(var(--v-theme-primary));
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), .12);
}

.flow-run-dialog__heading h2 { margin: 0 0 2px; font-size: 18px; }
.flow-run-dialog__heading p { margin: 0; color: rgba(var(--v-theme-on-surface), .58); font-size: 11px; }
.flow-run-dialog__close { position: absolute; top: 14px; right: 14px; }
.flow-run-dialog > .flow-field,
.flow-run-dialog > .flow-run-form,
.flow-run-dialog > .cx-alert,
.flow-run-dialog > small,
.flow-run-dialog > .flow-advanced,
.flow-run-dialog__actions { grid-column: 1 / -1; }

.flow-run-form { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.flow-field { display: block; margin-bottom: 14px; }
.flow-field > span { display: block; margin-bottom: 6px; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; }
.flow-field > small { display: block; margin: -2px 0 5px; color: rgba(var(--v-theme-on-surface), .55); font-size: 9px; }
.flow-field em { color: rgb(var(--v-theme-error)); font-style: normal; }
.flow-run-form .flow-field { margin: 0; }

.flow-run-button {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
  min-height: 34px;
  padding: 6px 14px;
  color: rgb(var(--v-theme-on-primary));
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  border: 0;
  border-radius: 8px;
  background: rgb(var(--v-theme-primary));
  box-shadow: 0 4px 12px rgba(var(--v-theme-primary), .2);
  cursor: pointer;
}

.flow-run-button:disabled { opacity: .45; cursor: not-allowed; }
.flow-run-dialog__actions { display: flex; gap: 8px; justify-content: flex-end; }
.flow-run-dialog .cx-select { width: 100%; }

.flow-config-empty {
  padding: 10px;
  font-size: 11px;
  border: 1px dashed rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.flow-advanced { margin-bottom: 14px; }
.flow-advanced summary { padding: 10px 0; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; cursor: pointer; }
.flow-advanced__body { padding-top: 3px; }
.flow-advanced .cx-textarea { width: 100%; resize: vertical; font-size: 11px; }

.flow-boolean-input { display: flex; gap: 7px; align-items: center; min-height: 30px; font-size: 11px; }

/* file picker / auto shared dir / dynamic enum options */
.flow-file-input { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.flow-file-native { position: absolute; width: 1px; height: 1px; opacity: 0; overflow: hidden; }
.flow-file-label { cursor: pointer; text-align: center; }
.flow-file-label--disabled { opacity: .55; pointer-events: none; }
.flow-file-chosen { display: inline-flex; align-items: center; gap: 4px; color: rgb(var(--v-theme-success)); font-size: 10px; }
.flow-file-error { width: 100%; color: rgb(var(--v-theme-error)); font-size: 10px; }
.flow-auto-dir { display: flex; align-items: center; gap: 7px; padding: 8px 10px; border: 1px dashed rgb(var(--v-theme-outline-variant)); border-radius: 8px; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; }
.flow-enum-status { display: inline-flex; align-items: center; gap: 7px; font-size: 11px; }
.flow-enum-list { display: grid; gap: 3px; max-height: 150px; overflow-y: auto; padding: 6px 8px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 8px; }
.flow-enum-list label { display: flex; align-items: center; gap: 7px; font-size: 11px; cursor: pointer; }

/* array-of-object row editor (multi-rule split configuration) */
.flow-rules-editor { display: grid; gap: 7px; }
.flow-rule-row { display: grid; gap: 5px; padding: 8px 9px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 9px; background: rgb(var(--v-theme-surface-container)); }
.flow-rule-row__head { display: flex; align-items: center; justify-content: space-between; }
.flow-rule-row__head strong { font-size: 10px; color: rgba(var(--v-theme-on-surface), .6); }
.flow-rule-row__inline { display: flex; align-items: end; gap: 10px; }
.flow-rule-row__grow { flex: 1 1 auto; min-width: 0; }
.flow-rule-switch { display: flex !important; grid-template-columns: auto 1fr; align-items: center; gap: 5px !important; min-height: 34px; }
.flow-rule-switch input { accent-color: rgb(var(--v-theme-primary)); }
.flow-rule-row label { display: grid; gap: 3px; font-size: 10px; color: rgba(var(--v-theme-on-surface), .7); }
.flow-rule-row .cx-input { font-size: 12px; }
.flow-add-item { display: inline-flex; gap: 5px; align-items: center; justify-content: center; padding: 6px 8px; color: rgb(var(--v-theme-primary)); font: inherit; font-size: 10px; border: 1px dashed rgba(var(--v-theme-primary), .6); border-radius: 7px; background: rgba(var(--v-theme-primary), .05); cursor: pointer; }

@media (max-width: 850px) {
  .flow-run-form { grid-template-columns: 1fr; }
}
</style>
