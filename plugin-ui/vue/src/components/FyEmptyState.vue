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
  <div role="status" class="d-flex flex-column align-center text-center pa-8">
    <FyIcon v-if="isIconPath(icon)" :path="icon!" :size="48" class="mb-3 opacity-60" />
    <v-icon v-else :icon="icon" size="48" class="mb-3 opacity-60" />
    <div class="text-h6">{{ title }}</div>
    <div v-if="message" class="text-body-2 opacity-70 mt-1">{{ message }}</div>
    <div v-if="$slots.action" class="mt-4">
      <slot name="action" />
    </div>
  </div>
</template>
