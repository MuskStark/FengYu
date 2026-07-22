<script setup lang="ts">
/**
 * Toolbar: a flex row that wraps its default slot of controls. Collapses
 * gracefully on narrow viewports.
 */
</script>

<template>
  <div class="fy-toolbar" role="toolbar">
    <slot />
  </div>
</template>

<style scoped>
.fy-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
</style>
