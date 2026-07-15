<script setup lang="ts">
/**
 * Standard error state: an alert region with an icon, readable title/message,
 * and a default retry button that emits `retry`. Provide the `action` slot to
 * replace the default button with a custom control.
 */
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
  <v-alert
    role="alert"
    type="error"
    variant="tonal"
    :icon="icon"
    prominent
  >
    <div class="text-subtitle-1 font-weight-medium">{{ title }}</div>
    <div v-if="message" class="text-body-2">{{ message }}</div>
    <template #append>
      <slot name="action">
        <v-btn color="error" variant="tonal" data-action="retry" @click="emit('retry')">
          Retry
        </v-btn>
      </slot>
    </template>
  </v-alert>
</template>
