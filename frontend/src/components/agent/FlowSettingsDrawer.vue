<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseWorkflowSchema } from '@/components/agent/workflow'
import type { WorkflowRevisionSummary } from '@/api/types'

/**
 * Workflow settings drawer of the flow builder: name/description/goal plus the
 * visual input designer that edits the run-form JSON Schema.
 */
defineProps<{
  workflowId: string | null
  canSave: boolean
  published: boolean
  revision: number | null
  publishedRevision?: number | null
  hasUnpublishedChanges?: boolean
  revisions?: WorkflowRevisionSummary[]
  disabled?: boolean
}>()
const emit = defineEmits<{
  close: []
  save: []
  'toggle-publication': []
  restore: [revision: number]
  delete: []
}>()

const name = defineModel<string>('name', { required: true })
const description = defineModel<string>('description', { required: true })
const goal = defineModel<string>('goal', { required: true })
const schemaText = defineModel<string>('schemaText', { required: true })

const { t, locale } = useI18n()

const schema = computed(() => parseWorkflowSchema(schemaText.value))
const schemaFields = computed(() => Object.entries(schema.value.properties ?? {}))
const requiredInputs = computed(() => new Set(schema.value.required ?? []))

function formatRevisionDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(locale.value)
}

function writeSchema(next: Record<string, unknown>) {
  schemaText.value = JSON.stringify(next, null, 2)
}

function addWorkflowInput() {
  const properties = { ...(schema.value.properties ?? {}) }
  let sequence = Object.keys(properties).length + 1
  let inputName = `input${sequence}`
  while (properties[inputName]) inputName = `input${++sequence}`
  properties[inputName] = { type: 'string', title: t('agent.newInput') }
  writeSchema({ ...schema.value, type: 'object', properties })
}

function renameWorkflowInput(oldName: string, event: Event) {
  const nextName = (event.target as HTMLInputElement).value.trim().replace(/[^A-Za-z0-9_-]/g, '')
  if (!nextName || nextName === oldName || schema.value.properties?.[nextName]) return
  const properties = { ...(schema.value.properties ?? {}) }
  const property = properties[oldName]
  delete properties[oldName]
  properties[nextName] = property
  const required = (schema.value.required ?? []).map((item) => item === oldName ? nextName : item)
  writeSchema({ ...schema.value, properties, required })
}

function updateWorkflowInputProperty(inputName: string, key: 'title' | 'description' | 'type', event: Event) {
  const properties = { ...(schema.value.properties ?? {}) }
  const value = (event.target as HTMLInputElement | HTMLSelectElement).value
  properties[inputName] = { ...properties[inputName], [key]: value }
  writeSchema({ ...schema.value, properties })
}

function toggleWorkflowInputRequired(inputName: string, event: Event) {
  const required = new Set(schema.value.required ?? [])
  if ((event.target as HTMLInputElement).checked) required.add(inputName)
  else required.delete(inputName)
  writeSchema({ ...schema.value, required: [...required] })
}

function removeWorkflowInput(inputName: string) {
  const properties = { ...(schema.value.properties ?? {}) }
  delete properties[inputName]
  const required = (schema.value.required ?? []).filter((item) => item !== inputName)
  writeSchema({ ...schema.value, properties, required })
}
</script>

<template>
  <div class="flow-settings">
    <div class="flow-settings__title">
      {{ t('agent.workflowSettings') }}
      <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('flows.close')" @click="emit('close')"><i class="mdi mdi-close" /></button>
    </div>
    <label class="flow-field"><span>{{ t('agent.workflowName') }}</span><input v-model="name" class="cx-input" :disabled="disabled"></label>
    <label class="flow-field"><span>{{ t('agent.workflowDescription') }}</span><textarea v-model="description" class="cx-textarea" rows="3" :disabled="disabled" /></label>
    <label class="flow-field"><span>{{ t('agent.canvasGoalPlaceholder') }}</span><textarea v-model="goal" class="cx-textarea" rows="3" :disabled="disabled" /></label>

    <section class="flow-input-designer">
      <div class="flow-input-designer__heading">
        <h3><i class="mdi mdi-form-textbox" /> {{ t('agent.workflowInputs') }}</h3>
        <button class="flow-add-item" :disabled="disabled" @click="addWorkflowInput"><i class="mdi mdi-plus" /> {{ t('agent.addInput') }}</button>
      </div>
      <p class="cx-muted">{{ t('agent.workflowInputDesignerHint') }}</p>
      <div v-for="([inputName, property]) in schemaFields" :key="inputName" class="flow-input-definition">
        <div class="flow-input-definition__head">
          <input class="cx-input" :value="inputName" :disabled="disabled" :aria-label="t('agent.variableName')" @change="renameWorkflowInput(inputName, $event)">
          <select class="cx-select" :value="property.type || 'string'" :disabled="disabled" @change="updateWorkflowInputProperty(inputName, 'type', $event)"><option value="string">{{ t('agent.fieldType.string') }}</option><option value="number">{{ t('agent.fieldType.number') }}</option><option value="integer">{{ t('agent.fieldType.integer') }}</option><option value="boolean">{{ t('agent.fieldType.boolean') }}</option><option value="array">{{ t('agent.fieldType.array') }}</option><option value="object">{{ t('agent.fieldType.object') }}</option></select>
          <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.deleteWorkflowInput')" :disabled="disabled" @click="removeWorkflowInput(inputName)"><i class="mdi mdi-delete-outline" /></button>
        </div>
        <input class="cx-input" :value="property.title || ''" :placeholder="t('agent.inputDisplayName')" :disabled="disabled" @input="updateWorkflowInputProperty(inputName, 'title', $event)">
        <input class="cx-input" :value="property.description || ''" :placeholder="t('agent.inputHelpText')" :disabled="disabled" @input="updateWorkflowInputProperty(inputName, 'description', $event)">
        <label class="flow-checkbox"><input type="checkbox" :checked="requiredInputs.has(inputName)" :disabled="disabled" @change="toggleWorkflowInputRequired(inputName, $event)"><span>{{ t('agent.requiredAtRun') }}</span></label>
      </div>
      <div v-if="!schemaFields.length" class="flow-config-empty">{{ t('agent.noWorkflowInputs') }}</div>
    </section>

    <small class="cx-muted flow-settings__hint">{{ t('agent.workflowTemplateHint') }}</small>
    <details class="flow-advanced"><summary>{{ t('agent.advancedSchema') }}</summary><div class="flow-advanced__body"><textarea v-model="schemaText" class="cx-textarea mono" rows="9" :disabled="disabled" /></div></details>

    <details v-if="workflowId" class="flow-advanced flow-revisions">
      <summary>{{ t('agent.versionHistory') }} <span v-if="revision">· v{{ revision }}</span></summary>
      <div class="flow-advanced__body">
        <p v-if="published && hasUnpublishedChanges" class="flow-revisions__pending">
          <i class="mdi mdi-source-branch" /> {{ t('agent.unpublishedChanges', { revision: publishedRevision }) }}
        </p>
        <div v-if="revisions?.length" class="flow-revisions__list">
          <div v-for="item in revisions" :key="item.revision" class="flow-revision-row">
            <span><strong>v{{ item.revision }}</strong><small>{{ formatRevisionDate(item.publishedAt) }}</small></span>
            <span v-if="item.active" class="cx-chip cx-chip--success">{{ t('agent.activeVersion') }}</span>
            <button class="cx-btn cx-btn--outline" :disabled="disabled" @click="emit('restore', item.revision)">{{ t('agent.restoreVersion') }}</button>
          </div>
        </div>
        <p v-else class="cx-muted">{{ t('agent.noVersionHistory') }}</p>
      </div>
    </details>

    <div class="flow-settings__actions">
      <button class="cx-btn cx-btn--primary" :disabled="disabled || !canSave" @click="emit('save')"><i class="mdi mdi-content-save-outline" /> {{ t('agent.saveWorkflow') }}</button>
      <button v-if="workflowId" class="cx-btn cx-btn--outline" :disabled="disabled" @click="emit('toggle-publication')"><i class="mdi" :class="published && !hasUnpublishedChanges ? 'mdi-eye-off-outline' : 'mdi-robot-outline'" /> {{ published && !hasUnpublishedChanges ? t('agent.unpublish') : hasUnpublishedChanges ? t('agent.publishChanges') : t('agent.publishForAi') }}</button>
      <button v-if="workflowId" class="cx-btn cx-btn--outline flow-settings__delete" :disabled="disabled" @click="emit('delete')"><i class="mdi mdi-delete-outline" /> {{ t('agent.deleteWorkflow') }}</button>
    </div>
  </div>
</template>

<style scoped>
.flow-settings {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.flow-settings__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
}

.flow-field { display: block; margin-bottom: 14px; }
.flow-field > span { display: block; margin-bottom: 6px; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; }
.flow-field .cx-textarea { width: 100%; resize: vertical; font-size: 11px; }

.flow-input-designer { margin-bottom: 14px; }
.flow-input-designer__heading { display: flex; gap: 8px; align-items: center; justify-content: space-between; margin-bottom: 9px; }
.flow-input-designer__heading h3 { display: flex; gap: 6px; align-items: center; margin: 0; font-size: 12px; }
.flow-input-designer__heading h3 i { color: rgb(var(--v-theme-primary)); font-size: 15px; }
.flow-input-designer > p { margin: -3px 0 10px; font-size: 10px; line-height: 1.45; }

.flow-input-definition { display: flex; flex-direction: column; gap: 7px; margin-bottom: 8px; padding: 10px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 9px; background: rgb(var(--v-theme-surface-container)); }
.flow-input-definition__head { display: grid; grid-template-columns: minmax(0, 1fr) 110px auto; gap: 6px; }
.flow-input-definition .cx-input,
.flow-input-definition .cx-select { width: 100%; font-size: 10px; }
.flow-input-definition .flow-checkbox { margin: 0; }

.flow-config-empty {
  padding: 10px;
  font-size: 11px;
  border: 1px dashed rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.flow-checkbox { display: flex; gap: 8px; align-items: center; margin-bottom: 14px; font-size: 12px; }
.flow-add-item { display: inline-flex; gap: 5px; align-items: center; justify-content: center; padding: 6px 8px; color: rgb(var(--v-theme-primary)); font: inherit; font-size: 10px; border: 1px dashed rgba(var(--v-theme-primary), .6); border-radius: 7px; background: rgba(var(--v-theme-primary), .05); cursor: pointer; }

.flow-settings__hint { display: block; margin: -4px 0 16px; line-height: 1.5; }
.flow-settings__actions { display: flex; flex-direction: column; gap: 8px; }
.flow-settings__actions .cx-btn { justify-content: center; }
.flow-settings__delete { color: rgb(var(--v-theme-error)); }

.flow-advanced { margin-bottom: 14px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.flow-advanced summary { padding: 10px 0; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; cursor: pointer; }
.flow-advanced__body { padding-top: 3px; }
.flow-revisions__pending { display: flex; gap: 6px; align-items: center; margin: 0 0 8px; padding: 8px; color: rgb(var(--v-theme-warning)); font-size: 10px; border-radius: 7px; background: rgba(var(--v-theme-warning), .08); }
.flow-revisions__list { display: flex; flex-direction: column; gap: 6px; }
.flow-revision-row { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 6px; align-items: center; padding: 7px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 7px; }
.flow-revision-row > span:first-child { display: flex; flex-direction: column; min-width: 0; font-size: 10px; }
.flow-revision-row small { overflow: hidden; color: rgba(var(--v-theme-on-surface), .58); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.flow-revision-row .cx-btn { min-height: 25px; padding: 3px 7px; font-size: 9px; }
</style>
