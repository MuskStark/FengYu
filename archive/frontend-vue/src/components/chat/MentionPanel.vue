<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { watch, ref } from 'vue'
import type { MentionOption, MentionSection } from './mentionSearch'

/**
 * Mention completion panel, ported from ZCode's MentionPanel structure: grouped rows (group
 * headers only when more than one group), 34px option rows with label + weak description,
 * hover state independent of the keyboard-selected state. The parent owns selection state —
 * keyboard navigation happens on the textarea's keydown, this panel only renders and reports
 * mouse interactions (mousedown prevents focus loss from the textarea, per ZCode).
 */
const props = defineProps<{
  sections: MentionSection[]
  /** Index into the flattened option list across all sections. */
  selectedIndex: number
  loading: boolean
  /** Shown when no option matches (query) or before typing (hint). */
  emptyLabel: string
}>()
const emit = defineEmits<{
  select: [option: MentionOption]
  hover: [index: number]
}>()
const { t } = useI18n()

const CATEGORY_LABEL_KEYS: Record<string, string> = {
  file: 'aichat.mentionFiles',
  plugin: 'aichat.mentionPlugins',
  skill: 'aichat.mentionSkills',
  flow: 'aichat.mentionFlows',
}

/** [globalIndex, option] pairs so each row knows its position in the flattened order. */
function rows(section: MentionSection, sectionStart: number): { index: number; option: MentionOption }[] {
  return section.options.map((option, i) => ({ index: sectionStart + i, option }))
}

/** Offset of a section's first option in the flattened (keyboard-navigable) order. */
function sectionOffset(sectionIndex: number): number {
  let count = 0
  for (let i = 0; i < sectionIndex; i++) count += props.sections[i].options.length
  return count
}

const listRef = ref<HTMLElement | null>(null)
watch(() => props.selectedIndex, () => {
  listRef.value?.querySelector('[data-selected="true"]')?.scrollIntoView({ block: 'nearest' })
})
</script>

<template>
  <div class="cx-card mention-panel" role="listbox" :aria-label="t('aichat.mentionPanel')">
    <div ref="listRef" class="mention-panel__list">
      <template v-for="(section, sectionIndex) in sections" :key="section.category">
        <div v-if="sections.length > 1" class="mention-panel__header" aria-hidden="true">
          {{ t(CATEGORY_LABEL_KEYS[section.category] ?? 'aichat.mentionFiles') }}
        </div>
        <button
          v-for="{ index, option } in rows(section, sectionOffset(sectionIndex))"
          :key="option.id"
          class="mention-panel__option"
          :class="{ 'mention-panel__option--selected': index === selectedIndex }"
          role="option"
          :aria-selected="index === selectedIndex"
          :data-selected="index === selectedIndex"
          @mousedown.prevent
          @mouseenter="emit('hover', index)"
          @click="emit('select', option)"
        >
          <i class="mdi mention-panel__icon" :class="option.icon" aria-hidden="true" />
          <span class="mention-panel__label">{{ option.label }}</span>
          <span class="cx-muted mention-panel__description" :title="option.description">{{ option.description }}</span>
        </button>
      </template>
      <div v-if="loading" class="cx-muted mention-panel__status"><span class="cx-spin" /> {{ $t('common.loading') }}</div>
      <div v-else-if="sections.length === 0" class="cx-muted mention-panel__status">{{ emptyLabel }}</div>
    </div>
  </div>
</template>

<style scoped>
.mention-panel {
  overflow: hidden;
  padding: 5px;
}
.mention-panel__list {
  max-height: 224px;
  overflow-y: auto;
}
.mention-panel__header {
  height: 32px;
  display: flex;
  align-items: center;
  padding: 0 9px;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: rgb(var(--v-theme-secondary));
  opacity: 0.75;
}
.mention-panel__option {
  width: 100%;
  height: 34px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 9px;
  border: 0;
  border-radius: var(--cx-radius-sm);
  background: transparent;
  color: rgb(var(--v-theme-on-surface));
  font: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.mention-panel__option:hover { background: var(--cx-hover); }
.mention-panel__option--selected { background: var(--cx-hover-strong); }
.mention-panel__option:focus-visible {
  outline: 2px solid rgba(var(--v-theme-on-surface), 0.72);
  outline-offset: -2px;
}
.mention-panel__icon { font-size: 17px; flex: 0 0 auto; opacity: 0.8; }
.mention-panel__label {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mention-panel__description {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11.5px;
  text-align: right;
}
.mention-panel__status {
  height: 36px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 9px;
  font-size: 12px;
}
</style>
