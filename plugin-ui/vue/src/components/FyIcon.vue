<script setup lang="ts">
/**
 * Inline-Svg icon renderer for FengYu plugin UIs.
 *
 * Plugin UIs run in a sandboxed iframe whose `connect-src` is `none`, and the
 * Material Design Icons webfont cannot be reliably inlined by the production
 * build (the `@mdi/font` stylesheet declares two `src:` blocks; only the legacy
 * `.eot` survives, which modern browsers ignore — so `mdi-*` font glyphs render
 * as tofu squares). The robust, network-free path is to ship icons as inline
 * SVG `<path>` data, sourced from `@mdi/js` (a tree-shakeable bundle of path
 * constants). `FyIcon` renders that data; no font, no fetch, no CSP surface.
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
