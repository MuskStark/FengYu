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
  <header class="fy-page-header">
    <div class="fy-page-header__copy">
      <h1 class="fy-page-header__title">{{ title }}</h1>
      <p v-if="description" class="fy-page-header__description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="fy-page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.fy-page-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px 24px;
  margin-bottom: 22px;
}

.fy-page-header__copy {
  min-width: 0;
}

.fy-page-header__title {
  margin: 0;
  font-size: clamp(1.25rem, 2vw, 1.5rem);
  font-weight: 630;
  line-height: 1.3;
  letter-spacing: -0.02em;
}

.fy-page-header__description {
  max-width: 64ch;
  margin: 5px 0 0;
  color: rgb(var(--v-theme-secondary));
  font-size: 0.8125rem;
}

.fy-page-header__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

@media (max-width: 600px) {
  .fy-page-header {
    margin-bottom: 16px;
  }

  .fy-page-header__actions {
    width: 100%;
  }
}
</style>
