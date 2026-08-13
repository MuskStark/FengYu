<script setup lang="ts">
import { computed } from 'vue'
import {
  mdiAlertCircleOutline,
  mdiCheckCircleOutline,
  mdiInformationOutline,
} from '@mdi/js'
import FyIcon from './FyIcon.vue'

export type FyProgressStatus = 'idle' | 'running' | 'success' | 'warning' | 'error'

const props = withDefaults(
  defineProps<{
    label?: string
    detail?: string
    /** Progress in the 0–100 range. Omit for indeterminate progress. */
    modelValue?: number
    status?: FyProgressStatus
    indeterminate?: boolean
  }>(),
  {
    label: '',
    detail: '',
    modelValue: undefined,
    status: 'running',
    indeterminate: undefined,
  },
)

const isIndeterminate = computed(() => props.indeterminate ?? props.modelValue === undefined)
const progressValue = computed(() => Math.min(100, Math.max(0, props.modelValue ?? 0)))
const STATUS_ICONS: Partial<Record<FyProgressStatus, string>> = {
  success: mdiCheckCircleOutline,
  warning: mdiAlertCircleOutline,
  error: mdiAlertCircleOutline,
}
const icon = computed(() => STATUS_ICONS[props.status] ?? mdiInformationOutline)
</script>

<template>
  <section
    class="fy-progress"
    :data-status="status"
    role="status"
    aria-live="polite"
  >
    <div class="fy-progress__summary">
      <FyIcon :path="icon" :size="18" class="fy-progress__icon" />
      <div class="fy-progress__copy">
        <div v-if="label" class="fy-progress__label">{{ label }}</div>
        <div v-if="detail" class="fy-progress__detail">{{ detail }}</div>
      </div>
      <span v-if="!isIndeterminate" class="fy-progress__value">{{ Math.round(progressValue) }}%</span>
      <div v-if="$slots.actions" class="fy-progress__actions"><slot name="actions" /></div>
    </div>
    <v-progress-linear
      class="fy-progress__bar"
      :indeterminate="isIndeterminate"
      :model-value="progressValue"
      :color="status === 'error' ? 'error' : status === 'success' ? 'tertiary' : 'primary'"
      height="4"
      rounded
    />
  </section>
</template>

<style scoped>
.fy-progress {
  min-width: 0;
  padding: 12px 14px;
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
}

.fy-progress__summary {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.fy-progress__icon { flex: 0 0 auto; color: rgb(var(--v-theme-primary)); }
.fy-progress[data-status='success'] .fy-progress__icon { color: rgb(var(--v-theme-tertiary)); }
.fy-progress[data-status='warning'] .fy-progress__icon { color: rgb(var(--v-theme-warning)); }
.fy-progress[data-status='error'] .fy-progress__icon { color: rgb(var(--v-theme-error)); }
.fy-progress__copy { min-width: 0; flex: 1 1 auto; }
.fy-progress__label { font-size: 0.8125rem; font-weight: 610; overflow-wrap: anywhere; }
.fy-progress__detail { margin-top: 1px; color: rgb(var(--v-theme-secondary)); font-size: 0.75rem; overflow-wrap: anywhere; }
.fy-progress__value { flex: 0 0 auto; color: rgb(var(--v-theme-secondary)); font-size: 0.75rem; font-variant-numeric: tabular-nums; }
.fy-progress__actions { display: flex; flex: 0 0 auto; gap: 6px; }
.fy-progress__bar { margin-top: 10px; }

@media (max-width: 520px) {
  .fy-progress__summary { flex-wrap: wrap; }
  .fy-progress__actions { width: 100%; padding-left: 28px; }
}
</style>
