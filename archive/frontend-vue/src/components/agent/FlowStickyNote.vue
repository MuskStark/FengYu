<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import type { WorkflowNoteData } from './workflow'

/**
 * Flowise agentflow sticky note (canvas.css .agentflow-sticky-note):
 * #fee440 background, 4px radius, soft shadow, transparent textarea.
 */
const props = defineProps<NodeProps<WorkflowNoteData>>()
const emit = defineEmits<{ delete: [] }>()
const { t } = useI18n()
</script>

<template>
  <div class="af-note" :class="{ selected }">
    <Handle type="target" :position="Position.Left" class="af-note__handle" :connectable="false" />
    <div class="af-note__bar">
      <i class="mdi mdi-note-text-outline" />
      <button class="af-note__delete" :title="t('flows.deleteNote')" @click.stop="emit('delete')">
        <i class="mdi mdi-delete-outline" />
      </button>
    </div>
    <textarea
      class="af-note__text"
      :value="data.content"
      :placeholder="t('flows.notePlaceholder')"
      spellcheck="false"
      @input="data.content = ($event.target as HTMLTextAreaElement).value"
      @keydown.delete.stop
      @pointerdown.stop
    />
    <Handle type="source" :position="Position.Right" class="af-note__handle" :connectable="false" />
  </div>
</template>

<style scoped>
.af-note {
  padding: 12px;
  color: rgba(35, 30, 18, 0.92);
  background: #fee440; /* tokens.colors.nodes.stickyNote */
  border: none;
  border-radius: 4px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  min-width: 150px;
  min-height: 100px;
  cursor: grab;
  user-select: none;
}

.af-note.selected {
  outline: 2px solid #fff;
  outline-offset: 2px;
}

.af-note__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 6px;
  color: rgba(35, 30, 18, 0.55);
}

.af-note__bar i {
  font-size: 15px;
}

.af-note__delete {
  padding: 2px 4px;
  color: inherit;
  border: 0;
  border-radius: 6px;
  background: transparent;
  opacity: 0.6;
  cursor: pointer;
}

.af-note__delete:hover {
  opacity: 1;
  background: rgba(35, 30, 18, 0.12);
}

.af-note__text {
  width: 100%;
  height: 100%;
  min-height: 70px;
  padding: 0;
  color: inherit;
  border: none;
  background: transparent;
  resize: none;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.45;
  outline: 0;
  cursor: text;
}

.af-note__handle {
  visibility: hidden;
}
</style>
