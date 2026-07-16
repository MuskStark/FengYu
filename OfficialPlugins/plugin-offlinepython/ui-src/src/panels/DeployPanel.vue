<script setup lang="ts">
import { ref } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyFilePicker } from '@infinia/plugin-ui'
import { call, field, refPath } from '../rpc'

const props = defineProps<{ client: FengYuClient; locale: string }>()
const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

const bundle = ref<FileRef | null>(null)
const targetKind = ref<'global' | 'venv'>('global')
const venvPath = ref('')
const logs = ref<string[]>([])
const status = ref('idle')
const installing = ref(false)
const jobId = ref<string | null>(null)
let poll: ReturnType<typeof setInterval> | null = null

async function startInstall() {
  const zipPath = refPath(bundle.value)
  if (!zipPath) { emit('toast', 'Select a bundle ZIP first'); return }
  logs.value = []
  status.value = 'starting'
  installing.value = true
  const target: Record<string, unknown> = { kind: targetKind.value }
  if (targetKind.value === 'venv') target.venvPath = venvPath.value
  const res = await call(props.client, 'deploy.start', { zipPath, target })
  const id = field<string>(res, 'jobId')
  if (!res.success || !id) {
    status.value = 'error'
    installing.value = false
    emit('toast', res.summary)
    return
  }
  jobId.value = id
  poll = setInterval(pollStatus, 800)
}

async function pollStatus() {
  if (!jobId.value) return
  const s = await call(props.client, 'deploy.status', { jobId: jobId.value, cursor: logs.value.length })
  if (!s.success) return
  const sLogs = field<string[]>(s, 'logs') ?? []
  if (sLogs.length) logs.value.push(...sLogs)
  status.value = field<string>(s, 'status') ?? status.value
  if (field<boolean>(s, 'done')) {
    installing.value = false
    if (poll) { clearInterval(poll); poll = null }
    const err = field<string>(s, 'error')
    emit('toast', s.summary ? `Deploy ${status.value}` : (err ?? 'Deploy finished'))
  }
}

function cancel() {
  if (jobId.value) call(props.client, 'deploy.cancel', { jobId: jobId.value })
  installing.value = false
  if (poll) { clearInterval(poll); poll = null }
  status.value = 'cancelled'
}
</script>

<template>
  <v-card flat border>
    <v-card-text>
      <div class="mb-4">
        <FyFilePicker
          v-model="bundle"
          label="Select bundle ZIP"
          :extensions="['zip']"
        />
      </div>

      <v-radio-group v-model="targetKind" inline>
        <v-radio label="Global environment" value="global" />
        <v-radio label="New virtual environment" value="venv" />
      </v-radio-group>
      <v-text-field v-if="targetKind === 'venv'" v-model="venvPath" label="venv path"
        hint="Where to create the virtual environment" persistent-hint class="mb-4" />

      <div class="d-flex gap-2 mb-4 flex-wrap">
        <v-btn color="primary" :loading="installing" :disabled="installing" @click="startInstall">Start install</v-btn>
        <v-btn v-if="installing" color="error" variant="text" @click="cancel">Cancel</v-btn>
        <v-chip>{{ status }}</v-chip>
      </div>

      <v-sheet class="pa-3" color="rgb(30,30,46)" rounded>
        <pre class="text-body-2" style="white-space: pre-wrap; font-family: monospace; max-height: 360px; overflow: auto">{{
          logs.length ? logs.join('\n') : 'Install log will appear here…'
        }}</pre>
      </v-sheet>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.gap-2 { gap: 8px; }
</style>
