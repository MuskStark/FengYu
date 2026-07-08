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

const label: Record<ConnState, string> = {
  connecting: 'Connecting…',
  connected: 'Connected',
  reconnecting: 'Reconnecting…',
}
</script>

<template>
  <footer class="statusbar">
    <span class="dot" :class="state" />
    <span class="txt">{{ label[state] }}</span>
    <span class="grow" />
    <span class="txt muted">ZhiFlow 4.0.0</span>
  </footer>
</template>

<style scoped>
.statusbar {
  grid-area: statusbar;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 26px;
  padding: 0 12px;
  background: var(--sk-bg-elevated);
  border-top: 1px solid var(--sk-border);
  font-size: 11px;
  color: var(--sk-text-secondary);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sk-text-disabled);
}
.dot.connected {
  background: var(--sk-success);
}
.dot.reconnecting {
  background: var(--sk-warning);
}
.dot.connecting {
  background: var(--sk-text-disabled);
}
.grow {
  flex: 1;
}
.muted {
  color: var(--sk-text-disabled);
}
</style>
