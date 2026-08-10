<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { useConnectionStore, type ConnState } from '@/stores/connection'
import { useUpdateStore } from '@/stores/update'

const conn = useConnectionStore()
const update = useUpdateStore()
const router = useRouter()
const appVersion = __APP_VERSION__
let timer: number | undefined
let everConnected = false

async function poll() {
  if (conn.state === 'restarting') return
  try {
    const r = await api.health()
    if (r.status === 'ok') {
      everConnected = true
      conn.state = 'connected'
    } else {
      conn.state = everConnected ? 'reconnecting' : 'offline'
    }
  } catch {
    conn.state = everConnected ? 'reconnecting' : 'offline'
  }
}

onMounted(() => {
  void poll()
  timer = window.setInterval(poll, 5000)
  // Non-blocking update probe — badge stays hidden until a newer release is found.
  void update.check()
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

const statusKey: Record<ConnState, string> = {
  connecting: 'status.connecting',
  connected: 'status.connected',
  reconnecting: 'status.reconnecting',
  offline: 'status.offline',
  restarting: 'status.restarting',
}
const indicatorClass: Record<ConnState, string> = {
  connecting: 'status-indicator--connecting',
  connected: 'status-indicator--connected',
  reconnecting: 'status-indicator--reconnecting',
  offline: 'status-indicator--offline',
  restarting: 'status-indicator--restarting',
}
</script>

<template>
  <footer class="cx-statusbar">
    <span class="status-version">{{ $t('brand') }} {{ appVersion }}</span>
    <button
      v-if="update.updateAvailable"
      type="button"
      class="cx-chip cx-chip--warn status-update-badge"
      :title="$t('update.available', { version: update.latestVersion })"
      @click="router.push({ name: 'about' })"
    >
      <i class="mdi mdi-arrow-up-circle" aria-hidden="true" />
      {{ $t('update.newVersion') }} v{{ update.latestVersion }}
    </button>
    <span
      class="status-indicator"
      :class="indicatorClass[conn.state]"
      :aria-label="$t(statusKey[conn.state])"
      aria-describedby="connection-status-tooltip"
      role="status"
      tabindex="0"
    >
      <span class="status-mark" aria-hidden="true" />
      <span id="connection-status-tooltip" class="status-tooltip" role="tooltip">
        {{ $t(statusKey[conn.state]) }}
      </span>
    </span>
  </footer>
</template>

<style scoped>
.cx-statusbar {
  position: relative;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
  height: 30px;
  padding: 0 12px;
  flex: 0 0 auto;
  background: rgb(var(--v-theme-surface-container));
  border-top: 1px solid rgb(var(--v-theme-outline-variant));
  color: rgb(var(--v-theme-secondary));
}
.status-version { font-size: 12px; }
.status-update-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 8px;
  font-size: 11px;
  line-height: 1.6;
  border: none;
  cursor: pointer;
  text-decoration: none;
}
.status-update-badge .mdi { font-size: 13px; }
.status-indicator {
  position: relative;
  width: 16px;
  height: 20px;
  flex: 0 0 16px;
  display: grid;
  place-items: center;
  color: rgb(var(--v-theme-secondary));
  cursor: help;
}
.status-mark {
  width: 7px;
  height: 7px;
  display: block;
  border-radius: 50%;
  background: currentColor;
  animation: status-breathe 1.8s ease-in-out infinite;
}
.status-indicator--connecting { color: rgb(var(--v-theme-secondary)); }
.status-indicator--connected { color: rgb(var(--v-theme-tertiary)); }
.status-indicator--reconnecting { color: #d9a441; }
.status-indicator--restarting { color: rgb(var(--v-theme-primary)); }
.status-indicator--offline { color: rgb(var(--v-theme-error)); }
.status-indicator--offline .status-mark {
  position: relative;
  width: 10px;
  height: 10px;
  border-radius: 0;
  background: transparent;
  animation: none;
}
.status-indicator--offline .status-mark::before,
.status-indicator--offline .status-mark::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 0;
  width: 2px;
  height: 10px;
  border-radius: 2px;
  background: currentColor;
}
.status-indicator--offline .status-mark::before { transform: rotate(45deg); }
.status-indicator--offline .status-mark::after { transform: rotate(-45deg); }
.status-tooltip {
  position: absolute;
  right: 0;
  bottom: calc(100% + 7px);
  width: max-content;
  max-width: 180px;
  padding: 5px 8px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 7px;
  background: rgb(var(--v-theme-surface));
  color: rgb(var(--v-theme-on-surface));
  box-shadow: 0 6px 18px rgba(0, 0, 0, .18);
  font-size: 11px;
  line-height: 1.3;
  white-space: nowrap;
  opacity: 0;
  visibility: hidden;
  transform: translateY(3px);
  pointer-events: none;
  transition: opacity .12s ease, visibility .12s ease, transform .12s ease;
}
.status-indicator:hover .status-tooltip,
.status-indicator:focus .status-tooltip,
.status-indicator:focus-visible .status-tooltip {
  opacity: 1;
  visibility: visible;
  transform: translateY(0);
}
@keyframes status-breathe {
  0%, 100% { opacity: .45; transform: scale(.82); box-shadow: 0 0 0 0 currentColor; }
  50% { opacity: 1; transform: scale(1); box-shadow: 0 0 0 4px transparent; }
}
@media (prefers-reduced-motion: reduce) {
  .status-mark { animation: none; }
}
</style>
