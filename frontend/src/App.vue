<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { AUTH_EXPIRED_EVENT } from '@/api/client'
import { isDesktop } from '@/mf/desktop'
import { useThemeStore } from '@/stores/theme'
import AppShell from './shell/AppShell.vue'

const route = useRoute()
const theme = useThemeStore()
const { t } = useI18n()

// Vuetify emits `.v-theme--dark { --v-theme-* }` / `.v-theme--light { … }`.
// We no longer mount <v-app>, so stamp the matching class on our own root to
// resolve those CSS vars. Flips live with the theme store.
const themeClass = computed(() => `v-theme--${theme.theme}`)

// ── Credential-expiry banner (P2-17) ─────────────────────────────────────────
// The axios layer dispatches AUTH_EXPIRED_EVENT when the backend rejects the UI's
// token; show one persistent banner with a recovery path. In the desktop shell a
// reload re-runs the preload, which re-reads the sidecar's fresh token; browser
// builds embed the token at compile time, so there the only recourse is the
// administrator. The client-side cooldown keeps a burst of failing requests from
// stacking duplicates; this flag keeps the banner single until reload/dismiss.
const authExpired = ref(false)
const desktop = computed(() => isDesktop())

function onAuthExpired() {
  authExpired.value = true
}

onMounted(() => window.addEventListener(AUTH_EXPIRED_EVENT, onAuthExpired))
onBeforeUnmount(() => window.removeEventListener(AUTH_EXPIRED_EVENT, onAuthExpired))

function reloadApp() {
  window.location.reload()
}
</script>

<template>
  <div class="cx-root" :class="themeClass">
    <!-- Fixed overlay so it spans the setup branch and the full shell alike. -->
    <div v-if="authExpired" class="cx-auth-banner" role="alert">
      <i class="mdi mdi-shield-lock-outline" aria-hidden="true" />
      <span class="cx-auth-banner__body">
        {{ t('auth.sessionExpired') }}
        <span v-if="!desktop" class="cx-auth-banner__hint">{{ t('auth.browserHint') }}</span>
      </span>
      <button class="cx-btn cx-btn--sm cx-btn--outline" @click="reloadApp">
        <i class="mdi mdi-refresh" />{{ t('auth.reload') }}
      </button>
      <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('common.close')" @click="authExpired = false">
        <i class="mdi mdi-close" />
      </button>
    </div>
    <template v-if="route.name === 'setup'">
      <div class="cx-body">
        <router-view />
      </div>
    </template>
    <AppShell v-else />
  </div>
</template>

<style scoped>
.cx-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: rgb(var(--v-theme-background));
  color: rgb(var(--v-theme-on-surface));
}
.cx-body {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  display: flex;
}
.cx-auth-banner {
  position: fixed;
  z-index: 2400;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: min(640px, calc(100vw - 32px));
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, rgb(var(--v-theme-error)) 45%, transparent);
  border-radius: 11px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.28);
  font-size: 13px;
}
.cx-auth-banner > .mdi {
  font-size: 19px;
  color: rgb(var(--v-theme-error));
}
.cx-auth-banner__body {
  min-width: 0;
  display: grid;
  gap: 2px;
}
.cx-auth-banner__hint {
  color: rgb(var(--v-theme-secondary));
  font-size: 12px;
}
</style>
