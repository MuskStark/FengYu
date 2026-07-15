<script setup lang="ts">
/**
 * Page header: a title, optional description, and an `actions` slot for
 * toolbar buttons aligned to the trailing edge.
 */
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="d-flex flex-wrap align-center justify-space-between ga-4 mb-4">
    <div class="d-flex flex-column">
      <h1 class="text-h5 font-weight-medium">{{ title }}</h1>
      <p v-if="description" class="text-body-2 opacity-70 mt-1">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="d-flex align-center ga-2">
      <slot name="actions" />
    </div>
  </div>
</template>
