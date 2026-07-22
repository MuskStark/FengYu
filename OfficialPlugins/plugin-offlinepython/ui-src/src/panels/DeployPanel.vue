<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyFilePicker, FyPageHeader } from '@infinia/plugin-ui'
import { call, callChecked, field } from '../rpc'
import { readJobSnapshot, type UiJobStatus } from '../jobState'

type Translate = (key: string, ...args: (string | number)[]) => string

const props = defineProps<{ client: FengYuClient; t: Translate }>()
const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

const bundle = ref<FileRef | null>(null)
const targetKind = ref<'global' | 'venv'>('global')
const venvPath = ref('')
const logs = ref<string[]>([])
const status = ref<UiJobStatus>('idle')
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
  if (!bundle.value) { emit('toast', props.t('opb.deploy.bundleRequired')); return }
  logs.value = []
  status.value = 'starting'
  installing.value = true
  try {
    const target: Record<string, unknown> = { kind: targetKind.value }
    if (targetKind.value === 'venv') target.venvPath = venvPath.value
    const res = await callChecked(props.client, 'deploy.start', { zipPath: bundle.value, target })
    const id = field<string>(res, 'jobId')
    if (!id) {
      status.value = 'error'
      installing.value = false
      emit('toast', props.t('opb.deploy.failed'))
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
    const snapshot = readJobSnapshot(s)
    if (snapshot.logs.length) logs.value.push(...snapshot.logs)
    status.value = snapshot.status
    if (!snapshot.ok) {
      stopPolling()
      installing.value = false
      jobId.value = null
      emit('toast', snapshot.summary)
      return
    }
    if (snapshot.done) {
      installing.value = false
      stopPolling()
      jobId.value = null
      emit('toast', snapshot.error || snapshot.status === 'failed'
        ? props.t('opb.deploy.failed')
        : props.t('opb.deploy.completed', props.t(`opb.deploy.status.${snapshot.status}`)))
    }
  } catch (error) {
    stopPolling()
    installing.value = false
    status.value = 'error'
    emit('toast', errorText(error))
  }
}

async function cancel() {
  if (!jobId.value) return
  try {
    await callChecked(props.client, 'deploy.cancel', { jobId: jobId.value })
    installing.value = false
    stopPolling()
    jobId.value = null
    status.value = 'cancelled'
  } catch (error) {
    emit('toast', errorText(error))
  }
}

onUnmounted(stopPolling)

const statusClass = computed(() => ({
  'opb-status--running': status.value === 'starting' || status.value === 'running',
  'opb-status--success': status.value === 'done',
  'opb-status--error': status.value === 'failed' || status.value === 'error',
}))
const canInstall = computed(() => Boolean(bundle.value)
  && (targetKind.value === 'global' || Boolean(venvPath.value.trim()))
  && !installing.value)
</script>

<template>
  <FyPageHeader :title="t('opb.deploy.title')" :description="t('opb.deploy.description')" />

  <section class="opb-surface">
    <div class="opb-surface__section">
      <h2 class="opb-section-heading">{{ t('opb.deploy.bundleTitle') }}</h2>
      <p class="opb-section-copy">{{ t('opb.deploy.bundleHint') }}</p>
        <FyFilePicker
          v-model="bundle"
          :label="t('opb.deploy.selectZip')"
          :extensions="['zip']"
        />
    </div>

    <div class="opb-surface__section">
      <h2 class="opb-section-heading">{{ t('opb.deploy.targetTitle') }}</h2>
      <p class="opb-section-copy">{{ t('opb.deploy.targetHint') }}</p>
      <div class="opb-segment" role="group" :aria-label="t('opb.deploy.targetTitle')">
        <button type="button" :aria-pressed="targetKind === 'global'" @click="targetKind = 'global'">
          {{ t('opb.deploy.targetGlobal') }}
        </button>
        <button type="button" :aria-pressed="targetKind === 'venv'" @click="targetKind = 'venv'">
          {{ t('opb.deploy.targetVenv') }}
        </button>
      </div>
      <v-text-field
        v-if="targetKind === 'venv'"
        v-model="venvPath"
        :label="t('opb.deploy.venvPath')"
        :hint="t('opb.deploy.venvHint')"
        persistent-hint
        class="opb-deploy__venv"
      />
    </div>

    <div class="opb-surface__section">
      <div class="opb-actions">
        <v-btn color="primary" :loading="installing" :disabled="!canInstall" @click="startInstall">{{ t('opb.deploy.start') }}</v-btn>
        <v-btn v-if="installing" color="error" variant="text" @click="cancel">{{ t('opb.deploy.cancel') }}</v-btn>
        <span class="opb-status" :class="statusClass">{{ t(`opb.deploy.status.${status}`, status) }}</span>
      </div>
    </div>

    <div class="opb-surface__section">
      <h2 class="opb-section-heading">{{ t('opb.deploy.logTitle') }}</h2>
        <pre class="opb-log">{{
          logs.length ? logs.join('\n') : t('opb.deploy.logEmpty')
        }}</pre>
    </div>
  </section>
</template>

<style scoped>
.opb-deploy__venv {
  max-width: 560px;
  margin-top: 16px;
}
</style>
