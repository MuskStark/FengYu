<script setup lang="ts">
import { computed } from 'vue'

/**
 * Responsive content frame shared by official plugin screens.
 *
 * It centralizes the desktop/mobile gutters and readable maximum width so
 * business plugins do not need to copy page-container media queries.
 */
const props = withDefaults(
  defineProps<{
    /** Maximum content width. Numbers are interpreted as pixels. */
    maxWidth?: number | string
    /** Removes the maximum width for editor/canvas-style workspaces. */
    fluid?: boolean
    /** Lets editor-style pages fill the available viewport height. */
    fullHeight?: boolean
  }>(),
  {
    maxWidth: 1120,
    fluid: false,
    fullHeight: false,
  },
)

const resolvedMaxWidth = computed(() => {
  if (props.fluid) return 'none'
  return typeof props.maxWidth === 'number' ? `${props.maxWidth}px` : props.maxWidth
})
</script>

<template>
  <main
    :class="['fy-plugin-page', { 'fy-plugin-page--full-height': fullHeight }]"
    :style="{ maxWidth: resolvedMaxWidth }"
  >
    <slot />
  </main>
</template>

<style scoped>
.fy-plugin-page {
  container-name: fy-plugin-page;
  container-type: inline-size;
  width: 100%;
  min-width: 0;
  padding: 28px 32px 48px;
  margin-inline: auto;
}

.fy-plugin-page--full-height {
  min-height: 100%;
}

@media (max-width: 720px) {
  .fy-plugin-page {
    padding: 20px 16px 36px;
  }
}

@media (max-width: 420px) {
  .fy-plugin-page {
    padding-inline: 12px;
  }
}
</style>
