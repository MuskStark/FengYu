<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyDirectoryPicker, FyEmptyState, FyPageHeader } from '@infinia/plugin-ui'
import { mdiFolderOpenOutline } from '@mdi/js'
import { call, callChecked, field } from '../rpc'
import { readJobSnapshot, type UiJobStatus } from '../jobState'
import { buildWorkerConfig, configForm, DEFAULT_FORM, type WorkerConfig } from '../configState'

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

// ---- shared build/job state (was in BuildVerifyPanel) -----------------------
const logs = ref<string[]>([])
const status = ref<UiJobStatus>('idle')
const building = ref(false)
const jobId = ref<string | null>(null)
let poll: ReturnType<typeof setInterval> | null = null

// ---- config form state (was in ConfigPanel) ---------------------------------
const requirements = ref('')
const form = ref<ReturnType<typeof configForm>>({ ...DEFAULT_FORM })
const loading = ref(false)
const saved = ref(false) // whether the current form has been persisted to disk
let loadVersion = 0

// ---- stepper state ----------------------------------------------------------
const step = ref(1) // 1=config, 2=build, 3=verify

function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : props.t('opb.common.error')
}

function stopPolling() {
  if (poll) clearInterval(poll)
  poll = null
}

/** Reset all transient state when the project changes. */
function resetState() {
  stopPolling()
  logs.value = []
  status.value = 'idle'
  building.value = false
  jobId.value = null
  requirements.value = ''
  form.value = { ...DEFAULT_FORM }
  saved.value = false
  step.value = 1
}

async function selectProject(next: FileRef | null) {
  if (!next) return
  resetState()
  emit('update:project', next)
  // Ensure the project skeleton (config.json, requirements.txt, README.md)
  // exists before we try to load it — InitService is idempotent (skips files
  // that already exist), so this is safe for re-selecting an existing project.
  try {
    await callChecked(props.client, 'init', { projectDir: next })
  } catch (error) {
    // init is best-effort: if it fails (e.g. read-only), still attempt to load
    // whatever config is already on disk, and surface the issue.
    emit('toast', errorText(error))
  }
  await loadConfig()
}

// ---- config step ------------------------------------------------------------
async function loadConfig() {
  const version = ++loadVersion
  const project = props.project
  form.value = { ...DEFAULT_FORM }
  requirements.value = ''
  saved.value = true // assume clean until edited
  if (!project) return
  loading.value = true
  try {
    const req = await callChecked(props.client, 'requirements.get', { projectDir: project })
    if (version !== loadVersion) return
    requirements.value = String(field<string>(req, 'text') ?? '')
    const cfg = await callChecked(props.client, 'config.get', { projectDir: project, session: 'ui' })
    if (version !== loadVersion) return
    const workerCfg = field<WorkerConfig>(cfg, 'config')
    form.value = configForm(workerCfg)
  } catch (error) {
    if (version === loadVersion) emit('toast', errorText(error))
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function save() {
  const project = props.project
  if (!project) {
    // Should not happen (button is disabled), but never silently bail — the
    // original saveConfig bug was a silent `if (!project) return` here.
    emit('toast', props.t('opb.project.empty'))
    return
  }
  loading.value = true
  try {
    await callChecked(props.client, 'requirements.save', { projectDir: project, text: requirements.value })
    // Send the FULL config so the worker's `Gson.fromJson(..., BuildConfig.class)`
    // does not reset repository/pkg/bundle sections to Java defaults.
    const config = buildWorkerConfig(form.value, /* preserve depPlatforms */ undefined)
    await callChecked(props.client, 'config.save', { projectDir: project, session: 'ui', config })
    saved.value = true
    emit('toast', props.t('opb.config.saved'))
  } catch (error) {
    emit('toast', errorText(error))
  } finally {
    loading.value = false
  }
}

/** Mark the form dirty so the UI can warn about unsaved changes. */
function markDirty() {
  saved.value = false
}

// ---- build step -------------------------------------------------------------
async function startBuild() {
  if (!props.project) return
  logs.value = []
  status.value = 'starting'
  building.value = true
  try {
    const res = await callChecked(props.client, 'build.start', { projectDir: props.project, session: 'ui' })
    const id = field<string>(res, 'jobId')
    if (!id) {
      status.value = 'error'
      building.value = false
      emit('toast', props.t('opb.build.failed'))
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
    const snapshot = readJobSnapshot(s)
    if (snapshot.logs.length) logs.value.push(...snapshot.logs)
    status.value = snapshot.status
    if (!snapshot.ok) {
      stopPolling()
      building.value = false
      jobId.value = null
      emit('toast', snapshot.summary)
      return
    }
    if (snapshot.done) {
      building.value = false
      stopPolling()
      jobId.value = null
      emit('toast', snapshot.error || snapshot.status === 'failed'
        ? props.t('opb.build.failed')
        : props.t('opb.build.completed', props.t(`opb.build.status.${snapshot.status}`)))
    }
  } catch (error) {
    stopPolling()
    building.value = false
    status.value = 'error'
    emit('toast', errorText(error))
  }
}

async function cancel() {
  if (!jobId.value) return
  try {
    await callChecked(props.client, 'build.cancel', { jobId: jobId.value })
    building.value = false
    stopPolling()
    jobId.value = null
    status.value = 'cancelled'
  } catch (error) {
    emit('toast', errorText(error))
  }
}

async function doPackage() {
  if (!props.project) return
  building.value = true
  try {
    const res = await callChecked(props.client, 'package', { projectDir: props.project, session: 'ui' })
    const zip = field<string>(res, 'zipPath') ?? ''
    emit('toast', props.t('opb.build.packaged', zip))
  } catch (error) {
    emit('toast', errorText(error))
  } finally {
    building.value = false
  }
}

// ---- verify step ------------------------------------------------------------
async function verify() {
  if (!props.project) return
  building.value = true
  try {
    await callChecked(props.client, 'verify', { projectDir: props.project, session: 'ui', scope: 'ALL' })
    emit('toast', props.t('opb.build.verifyOk'))
  } catch (error) {
    emit('toast', errorText(error))
  } finally {
    building.value = false
  }
}

// The build step is enabled once the config has been saved at least once for
// the current project. This guides the user through configure → build.
const canBuild = computed(() => saved.value && !building.value)

// React to external project changes (e.g. cleared from elsewhere).
watch(() => props.project, (next, prev) => {
  if (next?.id !== prev?.id) {
    resetState()
    if (next) {
      // Re-init + load for the new project.
      selectProject(next)
    }
  }
})

onUnmounted(stopPolling)
</script>

<template>
  <FyEmptyState
    v-if="!project"
    :title="t('opb.project.empty')"
    :message="t('opb.project.openPrompt')"
    :icon="mdiFolderOpenOutline"
  >
    <template #action>
      <FyDirectoryPicker mode="workspace" :label="t('opb.project.open')" @update:model-value="selectProject" />
    </template>
  </FyEmptyState>

  <template v-else>
    <FyPageHeader :title="project.name" :description="t('opb.nav.project')">
      <template #actions>
        <FyDirectoryPicker
          v-if="!building && !loading"
          mode="workspace"
          :label="t('opb.project.change')"
          :model-value="project"
          @update:model-value="selectProject"
        />
      </template>
    </FyPageHeader>

    <v-stepper v-model="step" alt-labels class="mt-2" :items="[t('opb.step.config'), t('opb.step.build'), t('opb.step.verify')]">
      <!-- Step 1: configure dependencies -->
      <template #[`item.1`]>
        <v-card flat border>
          <v-card-text>
            <div class="text-h6 mb-2">{{ t('opb.config.requirements') }}</div>
            <v-textarea
              v-model="requirements"
              rows="6"
              mono
              :placeholder="t('opb.config.requirementsPlaceholder')"
              hide-details
              class="mb-4"
              @input="markDirty"
            />

            <v-row>
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="form.pythonVersion"
                  :label="t('opb.config.pythonVersion')"
                  :hint="t('opb.config.pythonHint')"
                  persistent-hint
                  @input="markDirty"
                />
              </v-col>
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="form.platformsCsv"
                  :label="t('opb.config.platforms')"
                  :hint="t('opb.config.platformsHint')"
                  persistent-hint
                  @input="markDirty"
                />
              </v-col>
            </v-row>

            <div class="text-subtitle-2 mt-4 mb-2 text-medium-emphasis">{{ t('opb.config.download') }}</div>
            <v-switch v-model="form.onlyBinary" color="primary" :label="t('opb.config.onlyBinary')" hide-details @change="markDirty" />
            <v-switch v-model="form.recursive" color="primary" :label="t('opb.config.recursive')" hide-details @change="markDirty" />

            <v-expansion-panels class="mt-4" variant="accordion">
              <v-expansion-panel :title="t('opb.config.advanced')">
                <v-expansion-panel-text>
                  <v-row>
                    <v-col cols="12" sm="6">
                      <v-text-field v-model="form.output" :label="t('opb.config.output')" :hint="t('opb.config.outputHint')" persistent-hint @input="markDirty" />
                    </v-col>
                    <v-col cols="12" sm="6">
                      <v-text-field v-model="form.wheelDir" :label="t('opb.config.wheelDir')" :hint="t('opb.config.wheelDirHint')" persistent-hint @input="markDirty" />
                    </v-col>
                  </v-row>
                  <v-switch v-model="form.cache" color="primary" :label="t('opb.config.cache')" hide-details @change="markDirty" />
                  <v-switch v-model="form.upgradePip" color="primary" :label="t('opb.config.upgradePip')" hide-details @change="markDirty" />
                  <v-switch v-model="form.installer" color="primary" :label="t('opb.config.installer')" hide-details @change="markDirty" />

                  <div class="text-subtitle-2 mt-4 mb-2 text-medium-emphasis">{{ t('opb.config.pkgTitle') }}</div>
                  <v-switch v-model="form.zip" color="primary" :label="t('opb.config.pkgZip')" hide-details @change="markDirty" />
                  <v-switch v-model="form.pkgSha256" color="primary" :label="t('opb.config.pkgSha256')" hide-details @change="markDirty" />
                  <v-switch v-model="form.readme" color="primary" :label="t('opb.config.pkgReadme')" hide-details @change="markDirty" />
                </v-expansion-panel-text>
              </v-expansion-panel>
            </v-expansion-panels>

            <div class="d-flex gap-2 align-center mt-4 flex-wrap">
              <v-btn color="primary" :loading="loading" :disabled="loading" @click="save">{{ t('opb.config.save') }}</v-btn>
              <v-chip v-if="!saved" color="warning" variant="tonal" size="small">{{ t('opb.config.unsaved') }}</v-chip>
              <v-btn variant="text" :disabled="!saved" @click="step = 2">{{ t('opb.common.next') }}</v-btn>
            </div>
          </v-card-text>
        </v-card>
      </template>

      <!-- Step 2: download & package -->
      <template #[`item.2`]>
        <v-card flat border>
          <v-card-text>
            <div class="d-flex gap-2 mb-4 flex-wrap">
              <v-btn color="primary" :loading="building" :disabled="!canBuild" @click="startBuild">{{ t('opb.build.start') }}</v-btn>
              <v-btn variant="outlined" :disabled="building" @click="doPackage">{{ t('opb.build.package') }}</v-btn>
              <v-btn v-if="building" color="error" variant="text" @click="cancel">{{ t('opb.build.cancel') }}</v-btn>
              <v-chip>{{ t(`opb.build.status.${status}`, status) }}</v-chip>
              <v-btn variant="text" :disabled="building" @click="step = 3">{{ t('opb.common.next') }}</v-btn>
            </div>
            <v-sheet class="pa-3 bg-surface-variant" rounded border>
              <pre class="text-body-2" style="white-space: pre-wrap; max-height: 360px; overflow: auto">{{
                logs.length ? logs.join('\n') : t('opb.build.logEmpty')
              }}</pre>
            </v-sheet>
          </v-card-text>
        </v-card>
      </template>

      <!-- Step 3: verify -->
      <template #[`item.3`]>
        <v-card flat border>
          <v-card-text>
            <div class="d-flex gap-2 mb-4 flex-wrap">
              <v-btn color="primary" :loading="building" :disabled="building" @click="verify">{{ t('opb.build.verify') }}</v-btn>
              <v-chip>{{ t(`opb.build.status.${status}`, status) }}</v-chip>
            </div>
            <div class="text-body-2 text-medium-emphasis">{{ t('opb.verify.hint') }}</div>
          </v-card-text>
        </v-card>
      </template>
    </v-stepper>
  </template>
</template>

<style scoped>
.gap-2 { gap: 8px; }
</style>
