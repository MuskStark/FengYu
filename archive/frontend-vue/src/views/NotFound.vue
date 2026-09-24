<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <div style="flex: 1 1 auto; min-height: 0; overflow-y: auto">
    <div class="cx-page notfound">
      <div class="cx-card notfound-card">
        <i class="mdi mdi-compass-off-outline notfound-icon" aria-hidden="true" />
        <h1 class="cx-page-title notfound-title">{{ t('notFound.title') }}</h1>
        <p class="cx-muted notfound-hint">{{ t('notFound.hint') }}</p>
        <button class="cx-btn cx-btn--primary" @click="router.push('/')">
          <i class="mdi mdi-arrow-left" />{{ t('notFound.backHome') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notfound { display: flex; align-items: center; justify-content: center; min-height: 60vh; }
.notfound-card { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 40px 32px; }
.notfound-icon { font-size: 40px; color: rgb(var(--v-theme-secondary)); opacity: .7; }
.notfound-title { margin: 14px 0 6px; }
.notfound-hint { margin: 0 0 22px; font-size: 13px; }
</style>
