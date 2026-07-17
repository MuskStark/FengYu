<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyDirectoryPicker, FyEmptyState, FyPageHeader } from '@infinia/plugin-ui'
import { call, field, refPath } from '../rpc'

type Translate = (key: string, ...args: (string | number)[]) => string

const props = defineProps<{
  client: FengYuClient
  project: FileRef | null
  t: Translate
}>()
const emit = defineEmits<{
  (e: 'update:project', project: FileRef): void
  (e: 'toast', msg: string): void
}>()

const logs = ref<string[]>([])
const status = ref<string>('idle')
const building = ref(false)
const jobId = ref<string | null>(null)
let poll: ReturnType<typeof setInterval> | null = null

function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : props.t('opb.common.error')
}

function stopPolling() {
  if (poll) clearInterval(poll)
  poll = null
}

function selectProject(next: FileRef | null) {
  if (!next) return
  stopPolling()
  logs.value = []
  status.value = 'idle'
  building.value = false
  jobId.value = null
  emit('update:project', next)
}

async function startBuild() {
  if (!props.project) return
  logs.value = []
  status.value = 'starting'
  building.value = true
  try {
    const dir = refPath(props.project)!
    const res = await call(props.client, 'build.start', { projectDir: dir, session: 'ui' })
    const id = field<string>(res, 'jobId')
    if (!res.success || !id) {
      status.value = 'error'
      building.value = false
      emit('toast', res.success ? props.t('opb.build.failed') : res.summary)
      return
    }
    jobId.value = id
    poll = setInterval(pollStatus, 800)
  } catch (error) {
    status.value = 'error'
    building.value = false
    emit('toast', errorText(error))
  }
}

async function pollStatus() {
  if (!jobId.value) return
  try {
    const s = await call(props.client, 'build.status', { jobId: jobId.value, cursor: logs.value.length })
    if (!s.success) return
    const sLogs = field<string[]>(s, 'logs') ?? []
    if (sLogs.length) logs.value.push(...sLogs)
    status.value = field<string>(s, 'status') ?? status.value
    if (field<boolean>(s, 'done')) {
      building.value = false
      stopPolling()
      const err = field<string>(s, 'error')
      emit('toast', err ? props.t('opb.build.failed') : props.t('opb.build.completed', status.value))
    }
  } catch (error) {
    stopPolling()
    building.value = false
    status.value = 'error'
    emit('toast', errorText(error))
  }
}

function cancel() {
  if (jobId.value) call(props.client, 'build.cancel', { jobId: jobId.value })
  building.value = false
  stopPolling()
  status.value = 'cancelled'
}

async function verify() {
  if (!props.project) return
  building.value = true
  try {
    const res = await call(props.client, 'verify',
      { projectDir: refPath(props.project), session: 'ui', scope: 'ALL' })
    emit('toast', res.success ? props.t('opb.build.verifyOk') : res.summary)
  } catch (error) {
    emit('toast', errorText(error))
  } finally {
    building.value = false
  }
}

async function doPackage() {
  if (!props.project) return
  building.value = true
  try {
    const res = await call(props.client, 'package',
      { projectDir: refPath(props.project), session: 'ui' })
    const zip = field<string>(res, 'zipPath') ?? ''
    emit('toast', res.success ? props.t('opb.build.packaged', zip) : res.summary)
  } catch (error) {
    emit('toast', errorText(error))
  } finally {
    building.value = false
  }
}

onUnmounted(stopPolling)
</script>

<template>
  <FyEmptyState
    v-if="!project"
    :title="t('opb.project.empty')"
    :message="t('opb.build.openPrompt')"
    icon="mdi-folder-open-outline"
  >
    <template #action>
      <FyDirectoryPicker :label="t('opb.project.open')" @update:model-value="selectProject" />
    </template>
  </FyEmptyState>
  <template v-else>
    <FyPageHeader :title="project.name" :description="t('opb.build.title')">
      <template #actions>
        <FyDirectoryPicker
          v-if="!building"
          :label="t('opb.project.change')"
          :model-value="project"
          @update:model-value="selectProject"
        />
      </template>
    </FyPageHeader>

    <v-card flat border>
      <v-card-text>
        <div class="d-flex gap-2 mb-4 flex-wrap">
          <v-btn color="primary" :loading="building" :disabled="building" @click="startBuild">{{ t('opb.build.start') }}</v-btn>
          <v-btn variant="outlined" :disabled="building" @click="verify">{{ t('opb.build.verify') }}</v-btn>
          <v-btn variant="outlined" :disabled="building" @click="doPackage">{{ t('opb.build.package') }}</v-btn>
          <v-btn v-if="building" color="error" variant="text" @click="cancel">{{ t('opb.build.cancel') }}</v-btn>
          <v-chip>{{ t(`opb.build.status.${status}`, status) }}</v-chip>
        </div>
        <v-sheet class="pa-3 bg-surface-variant" rounded border>
          <pre class="text-body-2" style="white-space: pre-wrap; max-height: 360px; overflow: auto">{{
            logs.length ? logs.join('\n') : t('opb.build.logEmpty')
          }}</pre>
        </v-sheet>
      </v-card-text>
    </v-card>
  </template>
</template>

<style scoped>
.gap-2 { gap: 8px; }
</style>
