<script setup lang="ts">
import { useI18n } from 'vue-i18n'

/**
 * First-paint skeleton shown while the SPA waits for the backend to become healthy.
 * The desktop shell creates the window before the JVM finishes booting (page load
 * overlaps backend startup), so this replaces the native splash as the waiting UI;
 * App.vue swaps it for the real shell once the health poll succeeds.
 */
const { t } = useI18n()
</script>

<template>
  <div class="cx-bootgate" role="status" :aria-label="t('boot.waiting')">
    <div class="cx-bootgate__brand">
      <span class="cx-bootgate__name">{{ t('brand') }}</span>
      <span class="cx-bootgate__sub">Infinia · FengYu</span>
    </div>
    <div class="cx-bootgate__progress" aria-hidden="true">
      <span class="cx-bootgate__bar" />
    </div>
    <span class="cx-bootgate__label">{{ t('boot.waiting') }}</span>
  </div>
</template>

<style scoped>
.cx-bootgate {
  flex: 1 1 auto;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  background: rgb(var(--v-theme-background));
  color: rgb(var(--v-theme-on-surface));
  /* The gate owns the whole window before the shell's drag regions exist. */
  -webkit-app-region: drag;
  app-region: drag;
}
.cx-bootgate__brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.cx-bootgate__name {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.cx-bootgate__sub {
  font-size: 12px;
  color: rgb(var(--v-theme-secondary));
}
.cx-bootgate__progress {
  width: 180px;
  height: 3px;
  border-radius: 2px;
  overflow: hidden;
  background: color-mix(in srgb, rgb(var(--v-theme-primary)) 18%, transparent);
}
.cx-bootgate__bar {
  display: block;
  width: 40%;
  height: 100%;
  border-radius: 2px;
  background: rgb(var(--v-theme-primary));
  animation: cx-bootgate-slide 1.1s ease-in-out infinite;
}
@keyframes cx-bootgate-slide {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(350%); }
}
.cx-bootgate__label {
  font-size: 13px;
  color: rgb(var(--v-theme-secondary));
}
</style>
