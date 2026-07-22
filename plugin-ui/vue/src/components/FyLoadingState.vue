<script setup lang="ts">
/**
 * Standard loading state: a status region with an indeterminate spinner and
 * optional label. Provide the `action` slot for a primary control (e.g.
 * "Cancel").
 */
withDefaults(
  defineProps<{
    label?: string
  }>(),
  {
    label: 'Loading…',
  },
)
</script>

<template>
  <div role="status" class="fy-loading-state">
    <v-progress-circular indeterminate size="18" width="2" />
    <div v-if="label" class="fy-loading-state__label">{{ label }}</div>
    <div v-if="$slots.action" class="fy-loading-state__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.fy-loading-state {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 54px;
  padding: 12px 14px;
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
}
.fy-loading-state__label { color: rgb(var(--v-theme-secondary)); font-size: 0.8125rem; }
.fy-loading-state__action { margin-left: auto; }
</style>
