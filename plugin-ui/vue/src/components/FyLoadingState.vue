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
  <div role="status" class="d-flex flex-column align-center text-center pa-8">
    <v-progress-circular indeterminate size="48" class="mb-3" />
    <div v-if="label" class="text-body-2 opacity-70">{{ label }}</div>
    <div v-if="$slots.action" class="mt-4">
      <slot name="action" />
    </div>
  </div>
</template>
