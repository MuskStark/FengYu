<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { api } from '@/api/client'
import { useConnectionStore, type ConnState } from '@/stores/connection'

const conn = useConnectionStore()
// Surface the build-time version into the component scope so the template
// resolves it (vue-tsc does not always resolve top-level `declare const`
// globals inside <template> expressions).
const appVersion = __APP_VERSION__
let timer: number | undefined
let everConnected = false

async function poll() {
  // While the wizard has flagged a restart, ignore transient poll results so
  // we don't flicker offline/reconnecting mid-restart.
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

const chipColor: Record<ConnState, string> = {
  connecting: 'default',
  connected: 'success',
  reconnecting: 'warning',
  offline: 'error',
  restarting: 'info',
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
  <v-system-bar>
    <v-icon size="small" :icon="icon[conn.state]" />
    <v-chip :color="chipColor[conn.state]" size="x-small" variant="flat" class="ml-1">
      {{ $t(statusKey[conn.state]) }}
    </v-chip>
    <v-spacer />
    <span class="text-medium-emphasis text-caption">ZhiFlow {{ appVersion }}</span>
  </v-system-bar>
</template>
