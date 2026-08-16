<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AgentTool } from '@/api/types'
import {
  humanizeWorkflowToolName,
  workflowToolCategory,
} from '@/components/agent/workflow'

/**
 * Flowise-style node palette: search box on top, one collapsible accordion
 * section per tool category. Items are dragged onto the canvas (or clicked to
 * append + auto-connect from the selected node).
 */
const props = defineProps<{
  tools: AgentTool[]
  disabled?: boolean
}>()
const emit = defineEmits<{
  add: [tool: AgentTool]
  dragstart: [event: DragEvent, tool: AgentTool]
  close: []
}>()

const { t } = useI18n()
const search = ref('')
const collapsed = ref(new Set<string>())

const filteredTools = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  if (!query) return props.tools
  return props.tools.filter((tool) =>
    `${tool.name} ${tool.localizedDescription || tool.description}`.toLocaleLowerCase().includes(query))
})

const groupedTools = computed(() => {
  const groups = new Map<string, AgentTool[]>()
  for (const tool of filteredTools.value) {
    const category = workflowToolCategory(tool)
    groups.set(category, [...(groups.get(category) ?? []), tool])
  }
  return [...groups.entries()]
})

function categoryIcon(category: string): string {
  if (category === 'browser') return 'mdi-web'
  if (category === 'email') return 'mdi-email-outline'
  if (category === 'excel') return 'mdi-table'
  if (category === 'python') return 'mdi-language-python'
  if (category === 'skills') return 'mdi-lightbulb-outline'
  return 'mdi-puzzle-outline'
}

function toggleCategory(category: string) {
  const next = new Set(collapsed.value)
  if (next.has(category)) next.delete(category)
  else next.add(category)
  collapsed.value = next
}
</script>

<template>
  <div class="flow-palette">
    <div class="flow-palette__title">
      {{ t('agent.addNode') }}
      <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('flows.close')" @click="emit('close')"><i class="mdi mdi-close" /></button>
    </div>
    <div class="flow-palette__search">
      <i class="mdi mdi-magnify" />
      <input v-model="search" :placeholder="t('agent.searchNodes')" :aria-label="t('agent.searchNodes')">
    </div>
    <p class="cx-muted flow-palette__hint">{{ t('agent.canvasDragHint') }}</p>
    <section v-for="([category, categoryTools]) in groupedTools" :key="category" class="flow-palette__group">
      <button class="flow-palette__group-head" @click="toggleCategory(category)">
        <i class="mdi" :class="categoryIcon(category)" />
        <span>{{ t(`agent.toolCategory.${category}`) }}</span>
        <small>{{ categoryTools.length }}</small>
        <i class="mdi flow-palette__chevron" :class="collapsed.has(category) ? 'mdi-chevron-right' : 'mdi-chevron-down'" />
      </button>
      <div v-show="!collapsed.has(category)" class="flow-palette__group-body">
        <button
          v-for="tool in categoryTools"
          :key="tool.name"
          class="flow-palette__tool"
          draggable="true"
          :disabled="disabled"
          @dragstart="emit('dragstart', $event, tool)"
          @click="emit('add', tool)"
        >
          <i class="mdi mdi-hammer-wrench" />
          <span>
            <strong>{{ humanizeWorkflowToolName(tool.name) }}</strong>
            <small>{{ tool.localizedDescription || tool.description }}</small>
          </span>
        </button>
      </div>
    </section>
    <div v-if="!filteredTools.length" class="cx-muted flow-palette__empty">{{ t('agent.noTools') }}</div>
  </div>
</template>

<style scoped>
.flow-palette {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.flow-palette__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
}

.flow-palette__search {
  display: flex;
  gap: 7px;
  align-items: center;
  margin-bottom: 10px;
  padding: 0 10px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
  background: rgb(var(--v-theme-surface-container));
}

.flow-palette__search input {
  width: 100%;
  min-height: 36px;
  color: inherit;
  border: 0;
  outline: 0;
  background: transparent;
}

.flow-palette__hint {
  margin: 0 0 12px;
  font-size: 11px;
  line-height: 1.5;
}

.flow-palette__group { margin-bottom: 12px; }

.flow-palette__group-head {
  display: flex;
  width: 100%;
  gap: 6px;
  align-items: center;
  padding: 6px 4px;
  color: rgba(var(--v-theme-on-surface), .72);
  font: inherit;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: .06em;
  border: 0;
  border-radius: 7px;
  background: transparent;
  cursor: pointer;
}

.flow-palette__group-head:hover { color: rgb(var(--v-theme-on-surface)); background: rgba(var(--v-theme-on-surface), .05); }
.flow-palette__group-head i:first-child { color: rgb(var(--v-theme-primary)); font-size: 14px; }
.flow-palette__group-head small { margin-left: auto; opacity: .6; font-size: 9px; }
.flow-palette__chevron { opacity: .55; font-size: 14px; }

.flow-palette__group-body { display: flex; flex-direction: column; gap: 7px; padding: 2px 0 4px; }

.flow-palette__tool {
  display: flex;
  width: 100%;
  gap: 7px;
  align-items: flex-start;
  padding: 9px 10px;
  color: rgb(var(--v-theme-on-surface));
  text-align: left;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 9px;
  background: rgb(var(--v-theme-surface));
  cursor: grab;
}

.flow-palette__tool:hover { border-color: rgb(var(--v-theme-primary)); }
.flow-palette__tool:disabled { opacity: .5; cursor: not-allowed; }
.flow-palette__tool > i {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  color: rgb(var(--v-theme-primary));
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), .1);
}
.flow-palette__tool span { display: block; min-width: 0; }
.flow-palette__tool strong { display: block; font-size: 12px; overflow-wrap: anywhere; }
.flow-palette__tool small {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 3px;
  color: rgba(var(--v-theme-on-surface), .68);
  font-size: 10px;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.flow-palette__empty { padding: 20px 4px; text-align: center; font-size: 12px; }
</style>
