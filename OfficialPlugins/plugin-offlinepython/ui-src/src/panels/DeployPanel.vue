<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyFilePicker } from '@infinia/plugin-ui'
import { call, field, refPath } from '../rpc'

type Translate = (key: string, ...args: (string | number)[]) => string

const props = defineProps<{ client: FengYuClient; t: Translate }>()
const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

const bundle = ref<FileRef | null>(null)
const targetKind = ref<'global' | 'venv'>('global')
const venvPath = ref('')
const logs = ref<string[]>([])
const status = ref('idle')
const installing = ref(false)
const jobId = ref<string | null>(null)
let poll: ReturnType<typeof setInterval> | null = null

function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : props.t('opb.common.error')
}

function stopPolling() {
  if (poll) clearInterval(poll)
  poll = null
}

async function startInstall() {
  const zipPath = refPath(bundle.value)
  if (!zipPath) { emit('toast', props.t('opb.deploy.bundleRequired')); return }
  logs.value = []
  status.value = 'starting'
  installing.value = true
  try {
    const target: Record<string, unknown> = { kind: targetKind.value }
    if (targetKind.value === 'venv') target.venvPath = venvPath.value
    const res = await call(props.client, 'deploy.start', { zipPath, target })
    const id = field<string>(res, 'jobId')
    if (!res.success || !id) {
      status.value = 'error'
      installing.value = false
      emit('toast', res.success ? props.t('opb.deploy.failed') : res.summary)
      return
    }
    jobId.value = id
    poll = setInterval(pollStatus, 800)
  } catch (error) {
    status.value = 'error'
    installing.value = false
    emit('toast', errorText(error))
  }
}

async function pollStatus() {
  if (!jobId.value) return
  try {
    const s = await call(props.client, 'deploy.status', { jobId: jobId.value, cursor: logs.value.length })
    if (!s.success) return
    const sLogs = field<string[]>(s, 'logs') ?? []
    if (sLogs.length) logs.value.push(...sLogs)
    status.value = field<string>(s, 'status') ?? status.value
    if (field<boolean>(s, 'done')) {
      installing.value = false
      stopPolling()
      const err = field<string>(s, 'error')
      emit('toast', err ? props.t('opb.deploy.failed') : props.t('opb.deploy.completed', status.value))
    }
  } catch (error) {
    stopPolling()
    installing.value = false
    status.value = 'error'
    emit('toast', errorText(error))
  }
}

function cancel() {
  if (jobId.value) call(props.client, 'deploy.cancel', { jobId: jobId.value })
  installing.value = false
  stopPolling()
  status.value = 'cancelled'
}

onUnmounted(stopPolling)
</script>

<template>
  <v-card flat border>
    <v-card-text>
      <div class="mb-4">
        <FyFilePicker
          v-model="bundle"
          :label="t('opb.deploy.selectZip')"
          :extensions="['zip']"
        />
      </div>

      <v-radio-group v-model="targetKind" inline>
        <v-radio :label="t('opb.deploy.targetGlobal')" value="global" />
        <v-radio :label="t('opb.deploy.targetVenv')" value="venv" />
      </v-radio-group>
      <v-text-field v-if="targetKind === 'venv'" v-model="venvPath" :label="t('opb.deploy.venvPath')"
        :hint="t('opb.deploy.venvHint')" persistent-hint class="mb-4" />

      <div class="d-flex gap-2 mb-4 flex-wrap">
        <v-btn color="primary" :loading="installing" :disabled="installing" @click="startInstall">{{ t('opb.deploy.start') }}</v-btn>
        <v-btn v-if="installing" color="error" variant="text" @click="cancel">{{ t('opb.deploy.cancel') }}</v-btn>
        <v-chip>{{ t(`opb.deploy.status.${status}`, status) }}</v-chip>
      </div>

      <v-sheet class="pa-3 bg-surface-variant" rounded border>
        <pre class="text-body-2" style="white-space: pre-wrap; max-height: 360px; overflow: auto">{{
          logs.length ? logs.join('\n') : t('opb.deploy.logEmpty')
        }}</pre>
      </v-sheet>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.gap-2 { gap: 8px; }
</style>
