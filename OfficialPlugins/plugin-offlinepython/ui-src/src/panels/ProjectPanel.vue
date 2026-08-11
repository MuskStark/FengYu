<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyDirectoryPicker, FyEmptyState, FyPageHeader } from '@infinia/plugin-ui'
import { mdiFolderOpenOutline } from '@mdi/js'
import { createPluginRpc, checked } from '../rpc'
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

// Typed RPC client generated from manifest rpc.methods. Path inputs (projectDir) are FileRef
// objects the host resolves to absolute path strings before the worker receives them, so the
// `as unknown as string` casts below encode that host-side resolution at the call boundary.
const rpc = createPluginRpc(props.client)
// Abort all in-flight RPC when the panel unmounts (transport-cancel), so navigating away during a
// build/config-load does not leave a dangling worker call. Domain job cancel is separate (build.cancel).
const abortController = new AbortController()
const signal = () => abortController.signal

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
    await checked(await rpc.init({ projectDir: next as unknown as string }, { signal: signal() }))
  } catch (error) {
    // init is best-effort: if it fails (e.g. read-only), still attempt to load
    // whatever config is already on disk, and surface the issue.
    emit('toast', errorText(error))
  }
  await loadConfig(next)
}

// ---- config step ------------------------------------------------------------
async function loadConfig(projectOverride?: FileRef) {
  const version = ++loadVersion
  const project = projectOverride ?? props.project
  form.value = { ...DEFAULT_FORM }
  requirements.value = ''
  saved.value = true // assume clean until edited
  if (!project) return
  loading.value = true
  try {
    const req = await checked(await rpc.requirementsGet({ projectDir: project as unknown as string }, { signal: signal() }))
    if (version !== loadVersion) return
    requirements.value = String(req.text ?? '')
    const cfg = await checked(await rpc.configGet({ projectDir: project as unknown as string, session: 'ui' }, { signal: signal() }))
    if (version !== loadVersion) return
    const workerCfg = cfg.config as WorkerConfig | undefined
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
    await checked(await rpc.requirementsSave({ projectDir: project as unknown as string, text: requirements.value }, { signal: signal() }))
    // Send the FULL config so the worker's merge onto config.json does not reset
    // repository/pkg/bundle sections to Java defaults. depPlatforms is preserved
    // on disk by the worker merge (the typed config record omits it by design).
    const config = buildWorkerConfig(form.value, /* preserve depPlatforms */ undefined)
    await checked(await rpc.configSave({ projectDir: project as unknown as string, session: 'ui', config }, { signal: signal() }))
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
    const res = await checked(await rpc.buildStart({ projectDir: props.project as unknown as string, session: 'ui' }, { signal: signal() }))
    const id = res.jobId
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
    const s = await rpc.buildStatus({ jobId: jobId.value, cursor: logs.value.length }, { signal: signal() })
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
    await checked(await rpc.buildCancel({ jobId: jobId.value }, { signal: signal() }))
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
    const res = await checked(await rpc.package({ projectDir: props.project as unknown as string, session: 'ui' }, { signal: signal() }))
    const zip = res.zipPath ?? ''
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
    await checked(await rpc.verify({ projectDir: props.project as unknown as string, session: 'ui', scope: 'ALL' }, { signal: signal() }))
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
const workflowSteps = computed(() => [
  tStep(1, props.t('opb.step.config')),
  tStep(2, props.t('opb.step.build')),
  tStep(3, props.t('opb.step.verify')),
])

function tStep(value: number, title: string) {
  return { value, title }
}

function canVisitStep(value: number): boolean {
  return value === 1 || saved.value
}

function goToStep(value: number) {
  if (canVisitStep(value) && !building.value && !loading.value) step.value = value
}

const statusClass = computed(() => ({
  'opb-status--running': status.value === 'starting' || status.value === 'running',
  'opb-status--success': status.value === 'done',
  'opb-status--error': status.value === 'failed' || status.value === 'error',
}))

onUnmounted(() => {
  stopPolling()
  abortController.abort()
})
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
    <FyPageHeader :title="project.name" :description="t('opb.project.description')">
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

    <nav class="opb-workflow" :aria-label="t('opb.project.workflow')">
      <button
        v-for="item in workflowSteps"
        :key="item.value"
        type="button"
        class="opb-workflow__step"
        :class="{ 'opb-workflow__step--active': step === item.value }"
        :aria-current="step === item.value ? 'step' : undefined"
        :disabled="!canVisitStep(item.value) || building || loading"
        @click="goToStep(item.value)"
      >
        <span class="opb-workflow__number">{{ item.value }}</span>
        <span>{{ item.title }}</span>
      </button>
    </nav>

    <section class="opb-surface opb-project-panel">
      <!-- Step 1: configure dependencies -->
      <template v-if="step === 1">
        <div class="opb-surface__section">
            <h2 class="opb-section-heading">{{ t('opb.config.requirements') }}</h2>
            <p class="opb-section-copy">{{ t('opb.config.requirementsHint') }}</p>
            <v-textarea
              v-model="requirements"
              rows="6"
              mono
              :placeholder="t('opb.config.requirementsPlaceholder')"
              hide-details
              class="opb-requirements"
              @input="markDirty"
            />
        </div>

        <div class="opb-surface__section">
            <h2 class="opb-section-heading">{{ t('opb.config.runtime') }}</h2>
            <p class="opb-section-copy">{{ t('opb.config.runtimeHint') }}</p>
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
        </div>

        <div class="opb-surface__section">
            <h2 class="opb-section-heading">{{ t('opb.config.download') }}</h2>
            <div class="opb-option-grid">
              <v-switch v-model="form.onlyBinary" color="primary" :label="t('opb.config.onlyBinary')" hide-details @change="markDirty" />
              <v-switch v-model="form.recursive" color="primary" :label="t('opb.config.recursive')" hide-details @change="markDirty" />
            </div>

            <v-expansion-panels class="opb-advanced" variant="accordion">
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
        </div>

        <div class="opb-surface__section opb-actions opb-actions--split">
            <div class="opb-actions">
              <v-chip v-if="!saved" color="warning" variant="tonal" size="small">{{ t('opb.config.unsaved') }}</v-chip>
              <span v-else class="opb-status opb-status--success">{{ t('opb.config.saved') }}</span>
            </div>
            <div class="opb-actions">
              <v-btn variant="text" :loading="loading" :disabled="loading" @click="save">{{ t('opb.config.save') }}</v-btn>
              <v-btn color="primary" variant="flat" :disabled="!saved" @click="step = 2">{{ t('opb.common.next') }}</v-btn>
            </div>
        </div>
      </template>

      <!-- Step 2: download & package -->
      <template v-else-if="step === 2">
        <div class="opb-surface__section">
            <h2 class="opb-section-heading">{{ t('opb.build.title') }}</h2>
            <p class="opb-section-copy">{{ t('opb.build.description') }}</p>
            <div class="opb-actions">
              <v-btn color="primary" :loading="building" :disabled="!canBuild" @click="startBuild">{{ t('opb.build.start') }}</v-btn>
              <v-btn variant="outlined" :disabled="building" @click="doPackage">{{ t('opb.build.package') }}</v-btn>
              <v-btn v-if="building" color="error" variant="text" @click="cancel">{{ t('opb.build.cancel') }}</v-btn>
              <span class="opb-status" :class="statusClass">{{ t(`opb.build.status.${status}`, status) }}</span>
            </div>
        </div>
        <div class="opb-surface__section">
            <h3 class="opb-section-heading">{{ t('opb.build.logTitle') }}</h3>
              <pre class="opb-log">{{
                logs.length ? logs.join('\n') : t('opb.build.logEmpty')
              }}</pre>
        </div>
        <div class="opb-surface__section opb-actions opb-actions--split">
          <v-btn variant="text" :disabled="building" @click="step = 1">{{ t('opb.common.prev') }}</v-btn>
          <v-btn color="primary" variant="flat" :disabled="building" @click="step = 3">{{ t('opb.common.next') }}</v-btn>
        </div>
      </template>

      <!-- Step 3: verify -->
      <template v-else>
        <div class="opb-surface__section">
            <h2 class="opb-section-heading">{{ t('opb.step.verify') }}</h2>
            <p class="opb-section-copy">{{ t('opb.verify.hint') }}</p>
            <div class="opb-actions">
              <v-btn color="primary" :loading="building" :disabled="building" @click="verify">{{ t('opb.build.verify') }}</v-btn>
              <span class="opb-status" :class="statusClass">{{ t(`opb.build.status.${status}`, status) }}</span>
            </div>
        </div>
        <div class="opb-surface__section opb-actions">
          <v-btn variant="text" :disabled="building" @click="step = 2">{{ t('opb.common.prev') }}</v-btn>
        </div>
      </template>
    </section>
  </template>
</template>

<style scoped>
.opb-workflow {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-bottom: 12px;
}

.opb-workflow__step {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  min-height: 46px;
  padding: 7px 10px;
  color: rgb(var(--v-theme-secondary));
  text-align: left;
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
  font: inherit;
  font-size: 0.8125rem;
  cursor: pointer;
}

.opb-workflow__step--active {
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-surface-container-high));
  border-color: rgba(var(--v-theme-on-surface), 0.55);
}

.opb-workflow__step:disabled {
  cursor: default;
  opacity: 0.46;
}

.opb-workflow__number {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
  color: rgb(var(--v-theme-secondary));
  background: rgb(var(--v-theme-surface-container-high));
  border-radius: 50%;
  font-size: 0.6875rem;
  font-weight: 650;
}

.opb-workflow__step--active .opb-workflow__number {
  color: rgb(var(--v-theme-on-primary));
  background: rgb(var(--v-theme-primary));
}

.opb-option-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 18px;
}

.opb-advanced { margin-top: 14px; }
.opb-requirements :deep(textarea) { font-family: var(--fengyu-font-mono); }

@media (max-width: 600px) {
  .opb-workflow { grid-template-columns: 1fr; }
  .opb-workflow__step { min-height: 40px; }
  .opb-option-grid { grid-template-columns: 1fr; }
}
</style>
