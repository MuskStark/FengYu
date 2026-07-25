<script setup lang="ts">
/**
 * Inline-Svg icon renderer for FengYu plugin UIs.
 *
 * `createFengYuVuetify` supports regular `mdi-*` names through Vuetify's MDI
 * icon set. `FyIcon` is the complementary renderer for inline SVG `<path>`
 * data sourced from `@mdi/js`; it needs no font lookup or network access.
 *
 * Usage:
 * ```ts
 * import { mdiHammerWrench } from '@mdi/js'
 * <FyIcon :path="mdiHammerWrench" />
 * ```
 */
withDefaults(
  defineProps<{
    /** SVG path `d` data, e.g. `mdiHammerWrench` from `@mdi/js`. Empty renders nothing. */
    path?: string
    /** Icon edge length in px. */
    size?: number
  }>(),
  {
    path: '',
    size: 24,
  },
)
</script>

<template>
  <svg
    v-if="path"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    aria-hidden="true"
    class="fy-icon"
  >
    <path :d="path" fill="currentColor" />
  </svg>
</template>

<style scoped>
.fy-icon {
  display: inline-block;
  flex: 0 0 auto;
  vertical-align: middle;
}
</style>
