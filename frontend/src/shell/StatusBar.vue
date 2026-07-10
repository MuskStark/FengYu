<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api } from '@/api/client'

type ConnState = 'connecting' | 'connected' | 'reconnecting'

const state = ref<ConnState>('connecting')
let timer: number | undefined

async function poll() {
  try {
    const r = await api.health()
    state.value = r.status === 'ok' ? 'connected' : 'reconnecting'
  } catch {
    state.value = 'reconnecting'
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
}

const chipColor: Record<ConnState, string> = {
  connecting: 'default',
  connected: 'success',
  reconnecting: 'warning',
}
</script>

<template>
  <v-system-bar>
    <v-chip :color="chipColor[state]" size="x-small" variant="flat">
      {{ $t(statusKey[state]) }}
    </v-chip>
    <v-spacer />
    <span class="text-medium-emphasis text-caption">ZhiFlow 4.0.0</span>
  </v-system-bar>
</template>
