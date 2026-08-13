<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  missingRequiredWorkflowInputs,
  humanizeWorkflowToolName,
  workflowInputSummaries,
  workflowOutputSummaries,
  type WorkflowNodeData,
} from './workflow'

const props = defineProps<NodeProps<WorkflowNodeData>>()

const { t } = useI18n()
const inputs = computed(() => workflowInputSummaries(props.data.tool.inputSchema, props.data.argsText))
const outputs = computed(() => workflowOutputSummaries(props.data.tool.outputSchema))
const missingRequired = computed(() => missingRequiredWorkflowInputs(props.data.tool.inputSchema, props.data.argsText))
const configuredCount = computed(() => inputs.value.filter((field) => field.configured).length)
</script>

<template>
  <div class="workflow-tool-node" :class="{ selected, unavailable: !data.available, incomplete: missingRequired.length }" :title="data.tool.localizedDescription || data.tool.description">
    <Handle
      type="target"
      :position="Position.Left"
      :connectable="connectable"
      :title="t('agent.canvasConnectHere')"
    />
    <div class="workflow-tool-node__head">
      <span class="workflow-tool-node__icon"><i class="mdi mdi-hammer-wrench" /></span>
      <span class="workflow-tool-node__title">
        <strong>{{ humanizeWorkflowToolName(data.tool.name) }}</strong>
        <small>{{ data.tool.pluginId || 'FengYu' }}</small>
      </span>
      <i v-if="!data.available" class="mdi mdi-alert-circle-outline workflow-tool-node__warning" />
      <span v-else-if="missingRequired.length" class="workflow-tool-node__state workflow-tool-node__state--warn"><i class="mdi mdi-alert-outline" /> {{ missingRequired.length }}</span>
      <span v-else class="workflow-tool-node__state workflow-tool-node__state--ready"><i class="mdi mdi-check" /></span>
    </div>
    <div class="workflow-tool-node__body">
      <div class="workflow-tool-node__section-head">
        <span>{{ t('agent.nodeInput') }}</span>
        <small>{{ configuredCount }}/{{ inputs.length }} {{ t('agent.configured') }}</small>
      </div>
      <div v-if="inputs.length" class="workflow-tool-node__fields">
        <div v-for="field in inputs.slice(0, 3)" :key="field.name" class="workflow-tool-node__field">
          <span><i class="mdi" :class="field.source === 'node' ? 'mdi-link-variant' : field.source === 'workflow' ? 'mdi-form-textbox' : 'mdi-circle-small'" /> {{ field.label }}</span>
          <small :class="{ empty: !field.configured }">{{ field.value || (field.required ? t('agent.requiredInput') : t('agent.optionalInput')) }}</small>
        </div>
        <small v-if="inputs.length > 3" class="workflow-tool-node__more">+{{ inputs.length - 3 }} {{ t('agent.moreFields') }}</small>
      </div>
      <div v-else class="workflow-tool-node__empty-field">{{ t('agent.noInputRequired') }}</div>
      <div class="workflow-tool-node__outputs">
        <span>{{ t('agent.nodeOutput') }}</span>
        <span v-if="outputs.length" class="workflow-tool-node__output-pills"><small v-for="field in outputs.slice(0, 2)" :key="field.name">{{ field.label }}</small><small v-if="outputs.length > 2">+{{ outputs.length - 2 }}</small></span>
        <small v-else>{{ t('agent.completeResult') }}</small>
      </div>
    </div>
    <Handle
      type="source"
      :position="Position.Right"
      :connectable="connectable"
      :title="t('agent.canvasStartConnection')"
    />
  </div>
</template>

<style scoped>
.workflow-tool-node {
  width: 240px;
  min-height: 154px;
  color: rgb(var(--v-theme-on-surface));
  border: 1px solid rgb(var(--v-theme-outline));
  border-radius: 11px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 8px 24px rgba(0, 0, 0, .16);
  cursor: grab;
  user-select: none;
}

.workflow-tool-node:active {
  cursor: grabbing;
}

.workflow-tool-node.selected {
  border-color: rgb(var(--v-theme-primary));
  box-shadow: 0 0 0 2px rgba(var(--v-theme-primary), .2), 0 5px 16px rgba(0, 0, 0, .2);
}

.workflow-tool-node.incomplete:not(.unavailable) { border-color: rgba(var(--v-theme-warning), .75); }

.workflow-tool-node.unavailable {
  border-color: rgb(var(--v-theme-error));
  background: rgba(var(--v-theme-error), .08);
}

.workflow-tool-node__warning {
  color: rgb(var(--v-theme-error));
}

.workflow-tool-node__head {
  display: flex;
  gap: 7px;
  align-items: center;
  min-height: 51px;
  padding: 9px 10px 7px;
  border-bottom: 1px solid rgb(var(--v-theme-outline-variant));
}

.workflow-tool-node__icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 31px;
  height: 31px;
  color: rgb(var(--v-theme-primary));
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), .12);
}

.workflow-tool-node__title {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.workflow-tool-node__title strong {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-tool-node__title small {
  color: rgba(var(--v-theme-on-surface), .58);
  font-size: 9px;
  text-transform: uppercase;
  letter-spacing: .05em;
}

.workflow-tool-node__state {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  font-size: 9px;
  border-radius: 11px;
}
.workflow-tool-node__state--ready { color: rgb(var(--v-theme-success)); background: rgba(var(--v-theme-success), .12); }
.workflow-tool-node__state--warn { color: rgb(var(--v-theme-warning)); background: rgba(var(--v-theme-warning), .14); }

.workflow-tool-node__body { padding: 8px 10px 9px; }
.workflow-tool-node__section-head,
.workflow-tool-node__outputs {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  color: rgba(var(--v-theme-on-surface), .62);
  font-size: 9px;
  font-weight: 650;
  text-transform: uppercase;
  letter-spacing: .045em;
}
.workflow-tool-node__section-head small { font-size: 8px; font-weight: 500; text-transform: none; letter-spacing: 0; }
.workflow-tool-node__fields { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }
.workflow-tool-node__field { display: flex; gap: 7px; align-items: center; justify-content: space-between; min-width: 0; font-size: 9px; }
.workflow-tool-node__field > span { display: flex; min-width: 0; align-items: center; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workflow-tool-node__field > span i { color: rgb(var(--v-theme-primary)); }
.workflow-tool-node__field > small { max-width: 95px; overflow: hidden; color: rgba(var(--v-theme-on-surface), .64); text-overflow: ellipsis; white-space: nowrap; }
.workflow-tool-node__field > small.empty { color: rgb(var(--v-theme-warning)); font-style: italic; }
.workflow-tool-node__more { color: rgba(var(--v-theme-on-surface), .5); font-size: 8px; }
.workflow-tool-node__empty-field { margin-top: 5px; color: rgba(var(--v-theme-on-surface), .5); font-size: 9px; }
.workflow-tool-node__outputs { margin-top: 8px; padding-top: 7px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.workflow-tool-node__outputs > small { color: rgba(var(--v-theme-on-surface), .5); font-size: 8px; font-weight: 500; text-transform: none; letter-spacing: 0; }
.workflow-tool-node__output-pills { display: flex; min-width: 0; gap: 3px; }
.workflow-tool-node__output-pills small { max-width: 70px; padding: 2px 5px; overflow: hidden; color: rgb(var(--v-theme-primary)); font-size: 8px; font-weight: 500; text-overflow: ellipsis; text-transform: none; white-space: nowrap; letter-spacing: 0; border-radius: 8px; background: rgba(var(--v-theme-primary), .1); }

:deep(.vue-flow__handle) {
  width: 12px;
  height: 12px;
  border: 2px solid rgb(var(--v-theme-primary));
  background: rgb(var(--v-theme-surface));
}

:deep(.vue-flow__handle:hover),
:deep(.vue-flow__handle.connecting),
:deep(.vue-flow__handle.valid) {
  background: rgb(var(--v-theme-primary));
}
</style>
