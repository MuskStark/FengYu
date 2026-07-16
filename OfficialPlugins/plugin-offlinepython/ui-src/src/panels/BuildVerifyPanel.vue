<script setup lang="ts">
import { ref } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyEmptyState } from '@infinia/plugin-ui'
import { call, field, refPath } from '../rpc'

const props = defineProps<{
  client: FengYuClient
  project: FileRef | null
  locale: string
}>()
const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

const logs = ref<string[]>([])
const status = ref<string>('idle')
const building = ref(false)
const jobId = ref<string | null>(null)
let poll: ReturnType<typeof setInterval> | null = null

async function startBuild() {
  if (!props.project) return
  logs.value = []
  status.value = 'starting'
  building.value = true
  const dir = refPath(props.project)!
  const res = await call(props.client, 'build.start', { projectDir: dir, session: 'ui' })
  const id = field<string>(res, 'jobId')
  if (!res.success || !id) {
    status.value = 'error'
    building.value = false
    emit('toast', res.summary)
    return
  }
  jobId.value = id
  poll = setInterval(pollStatus, 800)
}

async function pollStatus() {
  if (!jobId.value) return
  const s = await call(props.client, 'build.status', { jobId: jobId.value, cursor: logs.value.length })
  if (!s.success) return
  const sLogs = field<string[]>(s, 'logs') ?? []
  if (sLogs.length) logs.value.push(...sLogs)
  status.value = field<string>(s, 'status') ?? status.value
  if (field<boolean>(s, 'done')) {
    building.value = false
    if (poll) { clearInterval(poll); poll = null }
    const err = field<string>(s, 'error')
    emit('toast', s.summary ? `Build ${status.value}` : (err ?? 'Build finished'))
  }
}

function cancel() {
  if (jobId.value) call(props.client, 'build.cancel', { jobId: jobId.value })
  building.value = false
  if (poll) { clearInterval(poll); poll = null }
  status.value = 'cancelled'
}

async function verify() {
  if (!props.project) return
  const res = await call(props.client, 'verify',
    { projectDir: refPath(props.project), session: 'ui', scope: 'ALL' })
  emit('toast', res.success ? `Verify OK` : res.summary)
}

async function doPackage() {
  if (!props.project) return
  const res = await call(props.client, 'package',
    { projectDir: refPath(props.project), session: 'ui' })
  const zip = field<string>(res, 'zipPath') ?? ''
  emit('toast', res.success ? `Packaged: ${zip}` : res.summary)
}
</script>

<template>
  <FyEmptyState v-if="!project" title="Open a project" description="Choose a project folder to build." />
  <v-card v-else flat border>
    <v-card-text>
      <div class="d-flex gap-2 mb-4 flex-wrap">
        <v-btn color="primary" :loading="building" :disabled="building" @click="startBuild">Build repository</v-btn>
        <v-btn variant="outlined" :disabled="building" @click="verify">Verify</v-btn>
        <v-btn variant="outlined" :disabled="building" @click="doPackage">Package bundle</v-btn>
        <v-btn v-if="building" color="error" variant="text" @click="cancel">Cancel</v-btn>
        <v-chip>{{ status }}</v-chip>
      </div>
      <v-sheet class="pa-3" color="rgb(30,30,46)" rounded>
        <pre class="text-body-2" style="white-space: pre-wrap; font-family: monospace; max-height: 360px; overflow: auto">{{
          logs.length ? logs.join('\n') : 'Build log will appear here…'
        }}</pre>
      </v-sheet>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.gap-2 { gap: 8px; }
</style>
