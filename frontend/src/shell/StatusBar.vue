<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { api } from '@/api/client'
import { useConnectionStore, type ConnState } from '@/stores/connection'

const conn = useConnectionStore()
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
const chipClass: Record<ConnState, string> = {
  connecting: '',
  connected: 'cx-chip--success',
  reconnecting: 'cx-chip--warn',
  offline: 'cx-chip--error',
  restarting: 'cx-chip--primary',
}
const icon: Record<ConnState, string> = {
  connecting: 'mdi-circle-medium',
  connected: 'mdi-check-circle-outline',
  reconnecting: 'mdi-autorenew',
  offline: 'mdi-lan-disconnect',
  restarting: 'mdi-restart',
}
</script>

<template>
  <footer class="cx-statusbar">
    <i class="mdi sm" :class="icon[conn.state]" />
    <span class="cx-chip" :class="chipClass[conn.state]">{{ $t(statusKey[conn.state]) }}</span>
    <span class="cx-grow" />
    <span class="cx-muted" style="font-size: 12px">Infinia {{ appVersion }}</span>
  </footer>
</template>

<style scoped>
.cx-statusbar {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  padding: 0 12px;
  flex: 0 0 auto;
  background: rgb(var(--v-theme-surface-container));
  border-top: 1px solid rgb(var(--v-theme-outline-variant));
  color: rgb(var(--v-theme-secondary));
}
</style>
