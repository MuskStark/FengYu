<script setup lang="ts">
/**
 * Standard error state: an alert region with an icon, readable title/message,
 * and a default retry button that emits `retry`. Provide the `action` slot to
 * replace the default button with a custom control.
 */
import { mdiAlertCircleOutline } from '@mdi/js'
import FyIcon from './FyIcon.vue'

withDefaults(
  defineProps<{
    title?: string
    message?: string
    /** MDI icon name; defaults to an alert outline. */
    icon?: string
  }>(),
  {
    title: 'Something went wrong',
    message: '',
    icon: 'mdi-alert-circle-outline',
  },
)

const emit = defineEmits<{ (event: 'retry'): void }>()
</script>

<template>
  <div class="fy-notice fy-notice--error" role="alert">
    <span class="fy-notice__icon" aria-hidden="true">
      <FyIcon v-if="icon === 'mdi-alert-circle-outline'" :path="mdiAlertCircleOutline" :size="18" />
      <v-icon v-else :icon="icon" size="18" />
    </span>
    <div class="fy-notice__copy">
      <div class="fy-notice__title">{{ title }}</div>
      <div v-if="message" class="fy-notice__message">{{ message }}</div>
    </div>
    <div class="fy-notice__action">
      <slot name="action">
        <v-btn size="small" color="error" variant="tonal" data-action="retry" @click="emit('retry')">
          Retry
        </v-btn>
      </slot>
    </div>
  </div>
</template>

<style scoped>
.fy-notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  background: rgb(var(--v-theme-error-container));
  border: 1px solid rgba(var(--v-theme-error), 0.35);
  border-radius: var(--fy-radius-md, 10px);
}
.fy-notice__icon { display: inline-flex; margin-top: 1px; color: rgb(var(--v-theme-error)); }
.fy-notice__copy { min-width: 0; flex: 1 1 auto; }
.fy-notice__title { font-size: 0.8125rem; font-weight: 620; }
.fy-notice__message { margin-top: 2px; color: rgb(var(--v-theme-on-error-container)); font-size: 0.8125rem; overflow-wrap: anywhere; }
.fy-notice__action { flex: 0 0 auto; }
@media (max-width: 520px) {
  .fy-notice { flex-wrap: wrap; }
  .fy-notice__action { width: 100%; padding-left: 28px; }
}
</style>
