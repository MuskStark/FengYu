<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import {
  flowTypeColor,
  type WorkflowSchemaProperty,
  type WorkflowStartNode,
} from './workflow'

/**
 * The Start node card: a compact visual surface for the workflow's run-time
 * inputs. Each declared input renders as a typed chip row (the same type colors
 * as every other port), so "what this flow needs before running" is readable
 * directly on the canvas. Clicking the card opens the ordinary right-side Start
 * inspector; the card itself never compiles into a plan step.
 */
const props = defineProps<NodeProps<WorkflowStartNode['data']> & {
  schemaFields?: Array<[string, WorkflowSchemaProperty]>
  requiredNames?: string[]
}>()
const emit = defineEmits<{ 'open-editor': [] }>()

const { t } = useI18n()

interface InputRow { name: string; title: string; type: string; required: boolean }

const inputRows = computed<InputRow[]>(() => {
  const required = new Set(props.requiredNames ?? [])
  return (props.schemaFields ?? []).map(([name, property]) => ({
    name,
    title: property.title || name,
    type: property.format === 'fengyu-file' ? 'file' : (property.type || 'string'),
    required: required.has(name),
  }))
})
</script>

<template>
  <div class="afs" @dblclick="emit('open-editor')">
    <div class="afs__card">
      <div class="afs__head">
        <span class="afs__badge"><i class="mdi mdi-play" /></span>
        <strong>{{ data?.title || t('agent.startNodeTitle') }}</strong>
      </div>
      <div v-if="inputRows.length" class="afs__rows">
        <div v-for="row in inputRows" :key="row.name" class="afs__row" :title="`{{inputs.${row.name}}}`">
          <span class="afs__dot" :style="{ background: flowTypeColor(row.type) }" />
          <span class="afs__name">{{ row.title }}</span>
          <span v-if="row.required" class="afs__req">{{ t('agent.required') }}</span>
        </div>
      </div>
      <div v-else class="afs__empty">{{ t('agent.startNodeNoInputs') }}</div>
      <small class="afs__hint">{{ t('agent.startNodeHint') }}</small>
      <Handle type="source" :position="Position.Right" class="afs__out" :connectable="connectable" />
    </div>
  </div>
</template>

<style scoped>
.afs { font-family: inherit; }

.afs__card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: max-content;
  min-width: 170px;
  max-width: 230px;
  padding: 11px 12px;
  color: rgb(var(--v-theme-on-surface));
  border: 1px solid rgba(var(--v-theme-success), .55);
  border-radius: 8px;
  background: rgba(var(--v-theme-success), .1);
  cursor: grab;
  overflow: visible;
}

.afs__card:hover { border-color: rgba(var(--v-theme-success), .9); }

.afs__head { display: flex; gap: 7px; align-items: center; font-size: 12.5px; }

.afs__badge {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  color: rgb(var(--v-theme-on-surface));
  border-radius: 8px;
  background: rgba(var(--v-theme-success), .55);
}

.afs__badge i { font-size: 16px; }

.afs__rows { display: flex; flex-direction: column; gap: 2px; }

.afs__row {
  display: flex;
  gap: 5px;
  align-items: center;
  font-size: 10px;
}

.afs__dot { flex: 0 0 auto; width: 7px; height: 7px; border-radius: 50%; }
.afs__name { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.afs__req { color: rgba(var(--v-theme-on-surface), .5); font-size: 8.5px; }

.afs__empty { color: rgba(var(--v-theme-on-surface), .5); font-size: 10px; }
.afs__hint { color: rgba(var(--v-theme-on-surface), .45); font-size: 8.5px; }

.afs__out {
  position: absolute;
  left: auto;
  right: -6px;
  top: 50%;
  transform: translate(0, -50%);
  width: 12px;
  height: 12px;
  border: 2px solid rgb(var(--v-theme-surface));
  border-radius: 50%;
  background: rgb(var(--v-theme-success));
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.12);
  cursor: crosshair;
}
</style>
