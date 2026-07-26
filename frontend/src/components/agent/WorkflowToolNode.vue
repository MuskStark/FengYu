<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { useI18n } from 'vue-i18n'
import type { WorkflowNodeData } from './workflow'

defineProps<NodeProps<WorkflowNodeData>>()

const { t } = useI18n()
</script>

<template>
  <div class="workflow-tool-node" :class="{ selected }">
    <Handle
      type="target"
      :position="Position.Left"
      :connectable="connectable"
      :title="t('agent.canvasConnectHere')"
    />
    <div class="workflow-tool-node__head">
      <i class="mdi mdi-hammer-wrench" />
      <strong>{{ data.tool.name }}</strong>
      <span>{{ id }}</span>
    </div>
    <div class="workflow-tool-node__body">{{ data.description }}</div>
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
  width: 168px;
  min-height: 72px;
  color: rgb(var(--v-theme-on-surface));
  border: 1px solid rgb(var(--v-theme-outline));
  border-radius: 8px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 5px 16px rgba(0, 0, 0, .18);
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

.workflow-tool-node__head {
  display: flex;
  gap: 7px;
  align-items: center;
  padding: 7px 9px 5px;
}

.workflow-tool-node__head strong {
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-tool-node__head span {
  margin-left: auto;
  color: rgba(var(--v-theme-on-surface), .58);
  font: 9px/1 monospace;
}

.workflow-tool-node__body {
  display: -webkit-box;
  padding: 0 9px 7px;
  color: rgba(var(--v-theme-on-surface), .68);
  font-size: 9px;
  line-height: 1.35;
  overflow: hidden;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}

:deep(.vue-flow__handle) {
  width: 11px;
  height: 11px;
  border: 2px solid rgb(var(--v-theme-primary));
  background: rgb(var(--v-theme-surface));
}

:deep(.vue-flow__handle:hover),
:deep(.vue-flow__handle.connecting),
:deep(.vue-flow__handle.valid) {
  background: rgb(var(--v-theme-primary));
}
</style>
