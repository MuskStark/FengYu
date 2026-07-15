<script setup lang="ts">
/**
 * Standard permission-denied notice: an alert region explaining that the user
 * lacks access, with an optional `action` slot (e.g. "Request access").
 */
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
  <v-alert
    role="alert"
    type="warning"
    variant="tonal"
    :icon="icon"
    prominent
  >
    <div class="text-subtitle-1 font-weight-medium">{{ title }}</div>
    <div v-if="message" class="text-body-2">{{ message }}</div>
    <template v-if="$slots.action" #append>
      <slot name="action" />
    </template>
  </v-alert>
</template>
