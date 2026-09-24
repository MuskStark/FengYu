<script setup lang="ts">
import type { WsTreeRow } from './wsTree'
import { wsRowIcon } from './wsTree'

/**
 * One recursive level of the workspace file tree. Toggling a directory and opening a file
 * bubble up as events so WorkspacePanel stays the single owner of tree state.
 */
const props = defineProps<{
  row: WsTreeRow
  depth: number
  expanded: Set<string>
  selected: string | null
}>()
const emit = defineEmits<{
  toggle: [row: WsTreeRow]
  open: [path: string]
}>()

function activate() {
  if (props.row.dir) emit('toggle', props.row)
  else emit('open', props.row.path)
}
</script>

<template>
  <li>
    <button
      class="ws-tree__row"
      :class="{ 'ws-tree__row--active': selected === row.path }"
      :style="{ paddingLeft: `${10 + depth * 14}px` }"
      @click="activate"
    >
      <i
        class="mdi ws-tree__chevron"
        :class="[
          row.dir ? (expanded.has(row.path) ? 'mdi-chevron-down' : 'mdi-chevron-right') : 'mdi-blank',
          { 'ws-tree__chevron--blank': !row.dir },
        ]"
      />
      <i class="mdi ws-tree__icon" :class="wsRowIcon(row, expanded.has(row.path))" />
      <span class="ws-tree__name">{{ row.name }}</span>
    </button>
    <ul v-if="row.dir && expanded.has(row.path)" class="ws-tree__list">
      <WsTreeNode
        v-for="child in row.children"
        :key="child.path"
        :row="child"
        :depth="depth + 1"
        :expanded="expanded"
        :selected="selected"
        @toggle="row => emit('toggle', row)"
        @open="path => emit('open', path)"
      />
    </ul>
  </li>
</template>

<style scoped>
.ws-tree__list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.ws-tree__row {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  height: 26px;
  padding: 0 8px;
  border: 0;
  border-radius: var(--cx-radius-sm);
  background: transparent;
  color: rgb(var(--v-theme-on-surface));
  font: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
  user-select: none;
}
.ws-tree__row:hover { background: var(--cx-hover); }
.ws-tree__row--active { background: var(--cx-hover-strong); }
.ws-tree__row:focus-visible {
  outline: 2px solid rgba(var(--v-theme-on-surface), 0.72);
  outline-offset: -2px;
}
.ws-tree__chevron { font-size: 16px; flex: 0 0 auto; opacity: 0.6; }
.ws-tree__chevron--blank { visibility: hidden; }
.ws-tree__icon { font-size: 17px; flex: 0 0 auto; opacity: 0.8; }
.ws-tree__name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
