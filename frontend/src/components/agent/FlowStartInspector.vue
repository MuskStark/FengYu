<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  flowTypeColor,
  humanizeWorkflowField,
  parseWorkflowSchema,
  type WorkflowSchema,
  type WorkflowSchemaProperty,
} from './workflow'

/**
 * Visual editor for the workflow's run-time inputs (the Start node's panel).
 * Edits the same JSON Schema the settings drawer exposes as raw JSON — every
 * change round-trips through schemaText so both editors stay in sync and the
 * persisted format (input_schema_json) is unchanged.
 */
const props = defineProps<{
  schemaText: string
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:schema-text': [text: string]
  close: []
}>()

const { t } = useI18n()

function copyInputReference(name: string) {
  void navigator.clipboard?.writeText(`{{inputs.${name}}}`)
}

/** Rendered via a helper: a literal {{inputs.x}} inside the template interpolation
 *  would terminate the mustache early. */
function inputReference(name: string): string {
  return `{{inputs.${name}}}`
}

type DesignerType = 'string' | 'textarea' | 'number' | 'boolean' | 'select' | 'file' | 'array' | 'object'

interface DesignerField {
  name: string
  title: string
  designerType: DesignerType
  required: boolean
  options: string[]
  example: string
}

const TYPE_CHOICES: Array<{ value: DesignerType; labelKey: string }> = [
  { value: 'string', labelKey: 'agent.inputTypeString' },
  { value: 'textarea', labelKey: 'agent.inputTypeTextarea' },
  { value: 'number', labelKey: 'agent.inputTypeNumber' },
  { value: 'boolean', labelKey: 'agent.inputTypeBoolean' },
  { value: 'select', labelKey: 'agent.inputTypeSelect' },
  { value: 'file', labelKey: 'agent.inputTypeFile' },
  { value: 'array', labelKey: 'agent.inputTypeArray' },
  { value: 'object', labelKey: 'agent.inputTypeObject' },
]

const schema = computed<WorkflowSchema>(() => parseWorkflowSchema(props.schemaText))

function designerTypeOf(property: WorkflowSchemaProperty): DesignerType {
  if (property.format === 'fengyu-file') return 'file'
  if (property.type === 'number' || property.type === 'integer') return 'number'
  if (property.type === 'boolean') return 'boolean'
  if (property.type === 'array') return 'array'
  if (property.type === 'object') return 'object'
  if (property.enum?.length) return 'select'
  if (property['x-fengyu-multiline']) return 'textarea'
  return 'string'
}

const fields = computed<DesignerField[]>(() => {
  const required = new Set(schema.value.required ?? [])
  return Object.entries(schema.value.properties ?? {}).map(([name, property]) => ({
    name,
    title: property.title || humanizeWorkflowField(name),
    designerType: designerTypeOf(property),
    required: required.has(name),
    options: (property.enum ?? []).map(String),
    example: Array.isArray(property.examples) && property.examples[0] !== undefined
      ? String(property.examples[0]) : '',
  }))
})

/** Serializes the designer rows back into the canonical schema text. */
function writeFields(next: DesignerField[]) {
  const previous = schema.value.properties ?? {}
  const properties: Record<string, WorkflowSchemaProperty> = {}
  const required: string[] = []
  for (const field of next) {
    // Start from the existing property so annotations the designer does not model
    // (x-fengyu-auto/-analyze/-enum/-options-from, default, description, nested
    // items, the fengyu-directory format, ...) survive an unrelated edit here.
    const source = previous[field.name]
    const property: WorkflowSchemaProperty = source
      ? { ...source }
      : {}
    property.title = field.title || humanizeWorkflowField(field.name)
    // Designer-owned facets are cleared before re-applying so a type switch
    // (file → number, select → string, ...) cannot leave them stale; formats the
    // designer never writes (fengyu-directory) survive the spread above. An enum
    // is only designer-owned when it was what made the property a "select" —
    // enum-on-number and similar authored facets are preserved untouched.
    if (source?.format === 'fengyu-file' && field.designerType !== 'file') delete property.format
    if (field.designerType !== 'textarea') delete property['x-fengyu-multiline']
    if (source && designerTypeOf(source) === 'select' && field.designerType !== 'select') {
      delete property.enum
    }
    const unchangedEnum = !!source?.enum
      && source.enum.map(String).join('\u0000') === field.options.join('\u0000')
    switch (field.designerType) {
      case 'number': property.type = 'number'; break
      case 'boolean': property.type = 'boolean'; break
      case 'array': property.type = 'array'; break
      case 'object': property.type = 'object'; break
      case 'file': property.type = 'string'; property.format = 'fengyu-file'; break
      case 'textarea': property.type = 'string'; property['x-fengyu-multiline'] = true; break
      case 'select':
        property.type = 'string'
        // Keep the parsed enum verbatim (it may hold non-string values) unless the
        // designer's options actually differ from what was read out of it.
        if (!unchangedEnum) property.enum = field.options.filter(Boolean)
        break
      default: property.type = 'string'; break
    }
    if (field.example) {
      const originalExamples = source?.examples
      property.examples = Array.isArray(originalExamples) && originalExamples.length > 1
        && field.example === String(originalExamples[0])
        ? originalExamples
        : [field.example]
    } else {
      delete property.examples
    }
    properties[field.name] = property
    if (field.required) required.push(field.name)
  }
  emit('update:schema-text', JSON.stringify({
    type: 'object',
    properties,
    ...(required.length ? { required } : {}),
  }, null, 2))
}

function patch(index: number, changes: Partial<DesignerField>) {
  writeFields(fields.value.map((field, fieldIndex) =>
    fieldIndex === index ? { ...field, ...changes } : field))
}

function addField() {
  const base = 'input'
  let name = base
  let counter = 2
  const taken = new Set(fields.value.map((field) => field.name))
  while (taken.has(name)) name = `${base}${counter++}`
  writeFields([...fields.value, {
    name,
    title: humanizeWorkflowField(name),
    designerType: 'string',
    required: false,
    options: [],
    example: '',
  }])
}

function removeField(index: number) {
  writeFields(fields.value.filter((_, fieldIndex) => fieldIndex !== index))
}

function typeLabel(field: DesignerField): string {
  const choice = TYPE_CHOICES.find((item) => item.value === field.designerType)
  return choice ? t(choice.labelKey) : field.designerType
}

function typeColor(field: DesignerField): string {
  switch (field.designerType) {
    case 'number': return flowTypeColor('number')
    case 'boolean': return flowTypeColor('boolean')
    case 'array': return flowTypeColor('array')
    case 'object': return flowTypeColor('object')
    case 'file': return flowTypeColor('file')
    default: return flowTypeColor('string')
  }
}
</script>

<template>
  <div class="fsd">
    <div class="fsd__title">
      {{ t('agent.startDesignerTitle') }}
      <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('flows.close')" @click="emit('close')"><i class="mdi mdi-close" /></button>
    </div>
    <p class="fsd__intro">{{ t('agent.startDesignerIntro') }}</p>

    <div v-for="(field, index) in fields" :key="field.name" class="fsd__field">
      <div class="fsd__field-head">
        <span class="fsd__dot" :style="{ background: typeColor(field) }" />
        <input
          class="cx-input fsd__name"
          :value="field.name"
          spellcheck="false"
          :disabled="disabled"
          :title="`{{inputs.${field.name}}}`"
          @change="patch(index, { name: ($event.target as HTMLInputElement).value.trim() })"
        >
        <label class="fsd__required" :title="t('agent.requiredAtRun')">
          <input
            type="checkbox"
            :checked="field.required"
            :disabled="disabled"
            @change="patch(index, { required: ($event.target as HTMLInputElement).checked })"
          >
          <span>{{ t('agent.required') }}</span>
        </label>
        <button class="cx-iconbtn cx-iconbtn--sm" :disabled="disabled" :title="t('agent.deleteWorkflowInput')" @click="removeField(index)"><i class="mdi mdi-delete-outline" /></button>
      </div>
      <div class="fsd__field-body">
        <label>
          <span>{{ t('agent.inputDisplayName') }}</span>
          <input class="cx-input" :value="field.title" :disabled="disabled" @input="patch(index, { title: ($event.target as HTMLInputElement).value })">
        </label>
        <label>
          <span>{{ t('agent.inputDesignerType') }}</span>
          <select class="cx-input" :value="field.designerType" :disabled="disabled" @change="patch(index, { designerType: ($event.target as HTMLSelectElement).value as DesignerType })">
            <option v-for="choice in TYPE_CHOICES" :key="choice.value" :value="choice.value">{{ t(choice.labelKey) }}</option>
          </select>
        </label>
        <label v-if="field.designerType === 'select'">
          <span>{{ t('agent.inputOptions') }}</span>
          <input
            class="cx-input"
            :value="field.options.join(', ')"
            :placeholder="t('agent.inputOptionsPlaceholder')"
            :disabled="disabled"
            @change="patch(index, { options: ($event.target as HTMLInputElement).value.split(',').map((item) => item.trim()).filter(Boolean) })"
          >
        </label>
        <label>
          <span>{{ t('agent.inputExample') }}</span>
          <input class="cx-input" :value="field.example" :placeholder="t('agent.inputExamplePlaceholder')" :disabled="disabled" @input="patch(index, { example: ($event.target as HTMLInputElement).value })">
        </label>
      </div>
      <small class="fsd__ref" :title="t('agent.copyReferencePath')" @click="copyInputReference(field.name)">
        <i class="mdi mdi-content-copy" /> {{ inputReference(field.name) }} · {{ typeLabel(field) }}
      </small>
    </div>

    <button class="fsd__add" :disabled="disabled" @click="addField"><i class="mdi mdi-plus" /> {{ t('agent.addInput') }}</button>

    <details class="fsd__json">
      <summary>{{ t('agent.advancedJsonInput') }}</summary>
      <textarea
        class="cx-textarea mono"
        rows="8"
        spellcheck="false"
        :value="schemaText"
        :disabled="disabled"
        @change="emit('update:schema-text', ($event.target as HTMLTextAreaElement).value)"
      />
    </details>
  </div>
</template>

<style scoped>
.fsd {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.fsd__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
}

.fsd__intro {
  margin: 0 0 14px;
  color: rgba(var(--v-theme-on-surface), .62);
  font-size: 11px;
  line-height: 1.5;
}

.fsd__field {
  margin-bottom: 10px;
  padding: 9px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.fsd__field-head { display: flex; gap: 7px; align-items: center; margin-bottom: 7px; }

.fsd__dot { flex: 0 0 auto; width: 9px; height: 9px; border-radius: 50%; }

.fsd__name { width: 130px; font-size: 11px; }

.fsd__required {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  flex: 1;
  color: rgba(var(--v-theme-on-surface), .65);
  font-size: 10px;
  white-space: nowrap;
}

.fsd__field-body { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.fsd__field-body label { display: flex; flex-direction: column; gap: 4px; color: rgba(var(--v-theme-on-surface), .64); font-size: 9px; }
.fsd__field-body .cx-input { width: 100%; font-size: 11px; }

.fsd__ref {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  margin-top: 6px;
  color: rgb(var(--v-theme-primary));
  font-size: 9.5px;
  font-family: ui-monospace, monospace;
  cursor: pointer;
}

.fsd__add {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  justify-content: center;
  padding: 7px 9px;
  color: rgb(var(--v-theme-primary));
  font: inherit;
  font-size: 11px;
  border: 1px dashed rgba(var(--v-theme-primary), .6);
  border-radius: 7px;
  background: rgba(var(--v-theme-primary), .05);
  cursor: pointer;
}

.fsd__add:disabled { opacity: .45; cursor: not-allowed; }

.fsd__json { margin-top: 14px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.fsd__json summary { padding: 10px 0; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; cursor: pointer; }
.fsd__json textarea { width: 100%; resize: vertical; font-size: 11px; }
</style>
