<script setup lang="ts">
/**
 * Standard empty state: a status region with an icon and text. Provide the
 * `action` slot for a primary call-to-action (e.g. "Create").
 */
withDefaults(
  defineProps<{
    title?: string
    message?: string
    /** MDI icon name; defaults to an empty inbox. */
    icon?: string
  }>(),
  {
    title: 'Nothing here yet',
    message: '',
    icon: 'mdi-inbox-outline',
  },
)
</script>

<template>
  <div role="status" class="d-flex flex-column align-center text-center pa-8">
    <v-icon :icon="icon" size="48" class="mb-3 opacity-60" />
    <div class="text-h6">{{ title }}</div>
    <div v-if="message" class="text-body-2 opacity-70 mt-1">{{ message }}</div>
    <div v-if="$slots.action" class="mt-4">
      <slot name="action" />
    </div>
  </div>
</template>
