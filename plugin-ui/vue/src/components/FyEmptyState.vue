<script setup lang="ts">
import { mdiInboxOutline } from '@mdi/js'
import FyIcon from './FyIcon.vue'

/**
 * Standard empty state: a status region with an icon and text. Provide the
 * `action` slot for a primary call-to-action (e.g. "Create").
 */
withDefaults(
  defineProps<{
    title?: string
    message?: string
    /**
     * Icon to display. Inline SVG path data (a string from `@mdi/js`, e.g.
     * `mdiFolderOpenOutline`) is preferred — the mdi webfont is not reliably
     * bundled into plugin iframes. A legacy `mdi-*` name falls back to
     * `v-icon` and may render as tofu squares.
     */
    icon?: string
  }>(),
  {
    title: 'Nothing here yet',
    message: '',
    icon: mdiInboxOutline,
  },
)

/** True when an icon string is SVG path data (from `@mdi/js`). */
function isIconPath(icon: string | undefined): boolean {
  return !!icon && /^m/i.test(icon)
}
</script>

<template>
  <div role="status" class="fy-state fy-state--empty">
    <span class="fy-state__icon" aria-hidden="true">
      <FyIcon v-if="isIconPath(icon)" :path="icon!" :size="22" />
      <v-icon v-else :icon="icon" size="22" />
    </span>
    <div class="fy-state__copy">
      <div class="fy-state__title">{{ title }}</div>
      <div v-if="message" class="fy-state__message">{{ message }}</div>
    </div>
    <div v-if="$slots.action" class="fy-state__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.fy-state {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 88px;
  padding: 16px;
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px dashed rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
}
.fy-state__icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  color: rgb(var(--v-theme-secondary));
  background: rgb(var(--v-theme-surface-container-high));
  border-radius: 10px;
}
.fy-state__copy { min-width: 0; flex: 1 1 auto; }
.fy-state__title { font-size: 0.875rem; font-weight: 610; }
.fy-state__message { margin-top: 2px; color: rgb(var(--v-theme-secondary)); font-size: 0.8125rem; }
.fy-state__action { flex: 0 0 auto; }
@media (max-width: 520px) {
  .fy-state { align-items: flex-start; flex-wrap: wrap; }
  .fy-state__action { width: 100%; padding-left: 50px; }
}
</style>
