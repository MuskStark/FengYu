<script setup lang="ts">
/**
 * Standard permission-denied notice: an alert region explaining that the user
 * lacks access, with an optional `action` slot (e.g. "Request access").
 */
import { mdiLockOutline } from '@mdi/js'
import FyIcon from './FyIcon.vue'

withDefaults(
  defineProps<{
    title?: string
    message?: string
    /** MDI icon name; defaults to a lock outline. */
    icon?: string
  }>(),
  {
    title: 'Permission required',
    message: 'You do not have access to this content.',
    icon: 'mdi-lock-outline',
  },
)
</script>

<template>
  <div class="fy-permission-notice" role="alert">
    <span class="fy-permission-notice__icon" aria-hidden="true">
      <FyIcon v-if="icon === 'mdi-lock-outline'" :path="mdiLockOutline" :size="18" />
      <v-icon v-else :icon="icon" size="18" />
    </span>
    <div class="fy-permission-notice__copy">
      <div class="fy-permission-notice__title">{{ title }}</div>
      <div v-if="message" class="fy-permission-notice__message">{{ message }}</div>
    </div>
    <div v-if="$slots.action" class="fy-permission-notice__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.fy-permission-notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  background: rgb(var(--v-theme-warning-container));
  border: 1px solid rgba(var(--v-theme-warning), 0.35);
  border-radius: var(--fy-radius-md, 10px);
}
.fy-permission-notice__icon { display: inline-flex; margin-top: 1px; color: rgb(var(--v-theme-warning)); }
.fy-permission-notice__copy { min-width: 0; flex: 1 1 auto; }
.fy-permission-notice__title { font-size: 0.8125rem; font-weight: 620; }
.fy-permission-notice__message { margin-top: 2px; color: rgb(var(--v-theme-on-warning-container)); font-size: 0.8125rem; overflow-wrap: anywhere; }
.fy-permission-notice__action { flex: 0 0 auto; }
</style>
