<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyFilePicker, FyPageHeader, FyProgress } from '@infinia/plugin-ui'
import { createPluginRpc, checked } from '../rpc'
import { readJobSnapshot, type UiJobStatus } from '../jobState'

type Translate = (key: string, ...args: (string | number)[]) => string

const props = defineProps<{ client: FengYuClient; t: Translate }>()
const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

// Typed RPC client generated from manifest rpc.methods. zipPath is a FileRef the host resolves to
// an absolute path string before the worker receives it; the cast encodes that boundary.
const rpc = createPluginRpc(props.client)
// Abort in-flight RPC on unmount (transport-cancel). Domain job cancel (deploy.cancel) is separate.
const abortController = new AbortController()
const signal = () => abortController.signal

const bundle = ref<FileRef | null>(null)
const targetKind = ref<'global' | 'venv'>('global')
const venvPath = ref('')
const logs = ref<string[]>([])
const status = ref<UiJobStatus>('idle')
const installing = ref(false)
const jobId = ref<string | null>(null)
let poll: ReturnType<typeof setInterval> | null = null

// ---- deployment-machine Python (auto-detect; manual override on failure) ----
// The build machine's configured interpreter is NOT reused here: deploy often runs
// on a different, offline machine whose interpreter (conda/pyenv/venv) is not on PATH.
// We auto-detect THIS machine's python; if that fails, the user types the path.
const detecting = ref(false)
const detectedExe = ref<string | null>(null)   // null = not detected (yet / at all)
const detectedVersion = ref<string | null>(null)
const manualExe = ref('')                        // user override; empty = rely on detection

function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : props.t('opb.common.error')
}

function stopPolling() {
  if (poll) clearInterval(poll)
  poll = null
}

/** The interpreter path that will be sent to deployStart: manual override wins, else detected. */
const resolvedPythonExe = computed(() => {
  const manual = manualExe.value.trim()
  if (manual) return manual
  return detectedExe.value ?? ''
})

/** Whether a usable interpreter is available (detected OR manually entered). */
const hasPython = computed(() => resolvedPythonExe.value.length > 0)

async function detectPython() {
  detecting.value = true
  try {
    const res = await rpc.pythonDetect({ executable: manualExe.value.trim() || undefined }, { signal: signal() })
    const d = res.detection
    if (d?.ok && d.executable) {
      detectedExe.value = d.executable
      detectedVersion.value = d.pythonVersion ?? null
    } else {
      detectedExe.value = null
      detectedVersion.value = null
    }
  } catch {
    detectedExe.value = null
    detectedVersion.value = null
  } finally {
    detecting.value = false
  }
}

onMounted(detectPython)

async function startInstall() {
  if (!bundle.value) { emit('toast', props.t('opb.deploy.bundleRequired')); return }
  if (!hasPython.value) {
    emit('toast', props.t('opb.deploy.notDetected'))
    return
  }
  logs.value = []
  status.value = 'starting'
  installing.value = true
  try {
    const target = targetKind.value === 'venv'
      ? { kind: 'venv' as const, pythonExe: resolvedPythonExe.value, venvPath: venvPath.value }
      : { kind: 'global' as const, pythonExe: resolvedPythonExe.value }
    const res = await checked(await rpc.deployStart({ zipPath: bundle.value as unknown as string, target }, { signal: signal() }))
    const id = res.jobId
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
    const s = await rpc.deployStatus({ jobId: jobId.value, cursor: logs.value.length }, { signal: signal() })
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
    await checked(await rpc.deployCancel({ jobId: jobId.value }, { signal: signal() }))
    installing.value = false
    stopPolling()
    jobId.value = null
    status.value = 'cancelled'
  } catch (error) {
    emit('toast', errorText(error))
  }
}

onUnmounted(() => {
  stopPolling()
  abortController.abort()
})

const statusClass = computed(() => ({
  'fy-status--running': status.value === 'starting' || status.value === 'running',
  'fy-status--success': status.value === 'done',
  'fy-status--error': status.value === 'failed' || status.value === 'error',
}))
const canInstall = computed(() => Boolean(bundle.value)
  && hasPython.value
  && (targetKind.value === 'global' || Boolean(venvPath.value.trim()))
  && !installing.value)
</script>

<template>
  <FyPageHeader :title="t('opb.deploy.title')" :description="t('opb.deploy.description')" />

  <section class="fy-surface">
    <div class="fy-surface__section">
      <h2 class="fy-section-title">{{ t('opb.deploy.bundleTitle') }}</h2>
      <p class="fy-section-copy">{{ t('opb.deploy.bundleHint') }}</p>
        <FyFilePicker
          v-model="bundle"
          :label="t('opb.deploy.selectZip')"
          :extensions="['zip']"
        />
    </div>

    <div class="fy-surface__section">
      <h2 class="fy-section-title">{{ t('opb.deploy.targetTitle') }}</h2>
      <p class="fy-section-copy">{{ t('opb.deploy.targetHint') }}</p>
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

    <div class="fy-surface__section">
      <h2 class="fy-section-title">{{ t('opb.deploy.pythonTitle') }}</h2>
      <p class="fy-section-copy">{{ t('opb.deploy.pythonHint') }}</p>
      <div class="fy-actions">
        <v-btn variant="outlined" :loading="detecting" :disabled="detecting || installing" @click="detectPython">
          {{ t('opb.deploy.redetect') }}
        </v-btn>
        <span v-if="detecting" class="fy-status">{{ t('opb.deploy.detecting') }}</span>
        <span v-else-if="detectedExe && !manualExe.trim()" class="fy-status fy-status--success">
          {{ t('opb.deploy.detected', detectedExe, detectedVersion ?? '') }}
        </span>
        <span v-else-if="!detectedExe && !manualExe.trim()" class="fy-status fy-status--error">
          {{ t('opb.deploy.notDetected') }}
        </span>
      </div>
      <v-text-field
        v-model="manualExe"
        :label="t('opb.deploy.pythonExe')"
        :placeholder="detectedExe || ''"
        persistent-placeholder
        class="opb-deploy__venv"
      />
    </div>

    <div class="fy-surface__section">
      <div class="fy-actions">
        <v-btn color="primary" :loading="installing" :disabled="!canInstall" @click="startInstall">{{ t('opb.deploy.start') }}</v-btn>
        <v-btn v-if="installing" color="error" variant="text" @click="cancel">{{ t('opb.deploy.cancel') }}</v-btn>
        <span v-if="!installing" class="fy-status" :class="statusClass">{{ t(`opb.deploy.status.${status}`, status) }}</span>
      </div>
      <FyProgress v-if="installing" :label="t(`opb.deploy.status.${status}`, status)" class="mt-3" />
    </div>

    <div class="fy-surface__section">
      <h2 class="fy-section-title">{{ t('opb.deploy.logTitle') }}</h2>
        <pre class="fy-log">{{
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
