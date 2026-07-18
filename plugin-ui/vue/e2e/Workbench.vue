<script setup lang="ts">
/**
 * Deterministic full-shell wizard fixture for visual regression coverage.
 * Theme and state are selected by URL query parameters; no worker or timer is
 * involved, so every screenshot captures the same application state.
 */
import { computed, onMounted, ref } from 'vue'
import {
  FyPageHeader,
  FyPluginShell,
  FyStepWizard,
} from '../src'
import type {
  FyWizardSnapshot,
  FyWizardStep,
  FyWizardStepState,
} from '../src'
import {
  mdiCheckCircleOutline,
  mdiCogOutline,
  mdiFileDocumentOutline,
  mdiHistory,
  mdiPlayCircleOutline,
  mdiTrayArrowDown,
} from '@mdi/js'

type FixtureState = 'normal' | 'validating' | 'error' | 'skipped' | 'complete'

const steps: FyWizardStep[] = [
  { value: 'source', title: 'Source file' },
  { value: 'mode', title: 'Import mode', optional: true },
  { value: 'output', title: 'Output settings' },
  { value: 'run', title: 'Run import' },
]

const fixtures: Record<FixtureState, FyWizardSnapshot> = {
  normal: {
    version: 1,
    activeStep: 'source',
    visitedPath: ['source'],
    states: {
      source: { status: 'active' },
      mode: { status: 'pending' },
      output: { status: 'pending' },
      run: { status: 'pending' },
    },
    completed: false,
  },
  validating: {
    version: 1,
    activeStep: 'mode',
    visitedPath: ['source', 'mode'],
    states: {
      source: { status: 'complete' },
      mode: { status: 'validating' },
      output: { status: 'pending' },
      run: { status: 'pending' },
    },
    completed: false,
  },
  error: {
    version: 1,
    activeStep: 'output',
    visitedPath: ['source', 'mode', 'output'],
    states: {
      source: { status: 'complete' },
      mode: { status: 'complete' },
      output: { status: 'error', error: 'Choose a writable output folder before continuing.' },
      run: { status: 'pending' },
    },
    completed: false,
  },
  skipped: {
    version: 1,
    activeStep: 'run',
    visitedPath: ['source', 'output', 'run'],
    states: {
      source: { status: 'complete' },
      mode: { status: 'skipped' },
      output: { status: 'complete' },
      run: { status: 'active' },
    },
    completed: false,
  },
  complete: {
    version: 1,
    activeStep: 'run',
    visitedPath: ['source', 'mode', 'output', 'run'],
    states: {
      source: { status: 'complete' },
      mode: { status: 'complete' },
      output: { status: 'complete' },
      run: { status: 'complete' },
    },
    completed: true,
  },
}

function selectedFixture(): FixtureState {
  const value = new URLSearchParams(window.location.search).get('state')
  return value === 'validating'
    || value === 'error'
    || value === 'skipped'
    || value === 'complete'
    ? value
    : 'normal'
}

function cloneStates(
  states: Record<string, FyWizardStepState>,
): Record<string, FyWizardStepState> {
  return Object.fromEntries(
    Object.entries(states).map(([id, state]) => [id, { ...state }]),
  )
}

const fixtureState = selectedFixture()
const fixture = fixtures[fixtureState]
const activeView = ref('import')
const wizardStates = ref(cloneStates(fixture.states))

// FyStepWizard normalizes restored active state to `active`. Echo the static
// controlled state after mount so validating/error fixtures retain their exact
// visual state while the snapshot still seeds the real visited path.
onMounted(() => {
  wizardStates.value = cloneStates(fixture.states)
})

const stateLabel = computed(() => ({
  normal: 'Ready to configure',
  validating: 'Checking import settings',
  error: 'Action required',
  skipped: 'Optional mode skipped',
  complete: 'Import complete',
})[fixtureState])
</script>

<template>
  <div data-workbench-shell>
    <FyPluginShell
      v-model="activeView"
      title="Import workbench"
      :items="[
        { value: 'import', title: 'Import', icon: mdiTrayArrowDown },
        { value: 'history', title: 'History', icon: mdiHistory },
        { value: 'settings', title: 'Settings', icon: mdiCogOutline },
      ]"
    >
      <main class="workbench" data-workbench>
        <FyPageHeader
          title="Create spreadsheet import"
          description="Configure a source, review the output, and run the import."
        >
          <template #actions>
            <span class="workbench__fixture-label">{{ stateLabel }}</span>
          </template>
        </FyPageHeader>

        <FyStepWizard
          :steps="steps"
          :model-value="fixture.activeStep"
          :states="wizardStates"
          :completed="fixture.completed"
          :snapshot="fixture"
        >
          <template #source>
            <section class="workbench__step-content" aria-labelledby="source-title">
              <div class="workbench__content-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path :d="mdiFileDocumentOutline" /></svg>
              </div>
              <div>
                <h2 id="source-title">Choose a source file</h2>
                <p>Select the workbook whose records you want to import.</p>
              </div>
              <v-text-field
                model-value="quarterly-forecast.xlsx"
                label="Selected file"
                variant="outlined"
                density="comfortable"
                readonly
                hide-details
              />
              <p class="workbench__hint">Excel workbook · 2.4 MB · Read access granted</p>
            </section>
          </template>

          <template #mode>
            <section class="workbench__step-content" aria-labelledby="mode-title">
              <div class="workbench__content-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path :d="mdiCogOutline" /></svg>
              </div>
              <div>
                <h2 id="mode-title">Configure import mode</h2>
                <p>Map worksheet rows into structured records.</p>
              </div>
              <v-select
                model-value="One record per row"
                :items="['One record per row']"
                label="Import strategy"
                variant="outlined"
                density="comfortable"
                readonly
                hide-details
              />
              <div v-if="fixtureState === 'validating'" class="workbench__validation" role="status">
                <v-progress-circular indeterminate size="18" width="2" />
                Validating column mappings…
              </div>
            </section>
          </template>

          <template #output>
            <section class="workbench__step-content" aria-labelledby="output-title">
              <div class="workbench__content-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path :d="mdiTrayArrowDown" /></svg>
              </div>
              <div>
                <h2 id="output-title">Review output settings</h2>
                <p>Choose where generated files will be written.</p>
              </div>
              <v-text-field
                model-value="Exports / Quarterly forecast"
                label="Output folder"
                variant="outlined"
                density="comfortable"
                readonly
                hide-details
              />
            </section>
          </template>

          <template #run>
            <section class="workbench__step-content" aria-labelledby="run-title">
              <div class="workbench__content-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path :d="mdiPlayCircleOutline" /></svg>
              </div>
              <div>
                <h2 id="run-title">Ready to import</h2>
                <p>The workbook is ready to process with the settings below.</p>
              </div>
              <dl class="workbench__summary">
                <div><dt>Source</dt><dd>quarterly-forecast.xlsx</dd></div>
                <div><dt>Mode</dt><dd>{{ fixtureState === 'skipped' ? 'Automatic' : 'One record per row' }}</dd></div>
                <div><dt>Destination</dt><dd>Exports / Quarterly forecast</dd></div>
              </dl>
            </section>
          </template>

          <template #complete>
            <section class="workbench__complete" aria-labelledby="complete-title">
              <div class="workbench__complete-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path :d="mdiCheckCircleOutline" /></svg>
              </div>
              <h2 id="complete-title">Import complete</h2>
              <p>1,024 records were created from quarterly-forecast.xlsx.</p>
              <v-btn color="primary" variant="tonal">Open results</v-btn>
            </section>
          </template>
        </FyStepWizard>
      </main>
    </FyPluginShell>
  </div>
</template>

<style scoped>
[data-workbench-shell] {
  min-width: 0;
  min-height: 100vh;
  background: rgb(var(--v-theme-background));
}

.workbench {
  width: min(100%, 1120px);
  padding: 28px 32px 40px;
  margin: 0 auto;
}

.workbench__fixture-label {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 4px 10px;
  font-size: 0.75rem;
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-surface-container-high));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 999px;
}

.workbench__step-content {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 18px;
  max-width: 720px;
}

.workbench__step-content > :not(.workbench__content-icon):not(div:first-of-type) {
  grid-column: 2;
}

.workbench__step-content h2,
.workbench__complete h2 {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  line-height: 1.5;
}

.workbench__step-content p,
.workbench__complete p {
  margin: 4px 0 0;
  font-size: 0.875rem;
  line-height: 1.5;
  opacity: 0.72;
}

.workbench__content-icon,
.workbench__complete-icon {
  display: grid;
  place-items: center;
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-surface-container-high));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 10px;
}

.workbench__content-icon {
  width: 40px;
  height: 40px;
}

.workbench__content-icon svg,
.workbench__complete-icon svg {
  width: 22px;
  height: 22px;
  fill: currentColor;
}

.workbench__hint {
  margin-top: -8px !important;
  font-size: 0.75rem !important;
}

.workbench__validation {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  font-size: 0.8125rem;
  background: rgb(var(--v-theme-surface-container));
  border-radius: 8px;
}

.workbench__summary {
  display: grid;
  gap: 1px;
  margin: 0;
  overflow: hidden;
  background: rgba(var(--v-border-color), var(--v-border-opacity));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 8px;
}

.workbench__summary div {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 12px;
  padding: 10px 12px;
  background: rgb(var(--v-theme-surface));
}

.workbench__summary dt,
.workbench__summary dd {
  margin: 0;
  font-size: 0.8125rem;
}

.workbench__summary dt {
  opacity: 0.68;
}

.workbench__complete {
  display: grid;
  justify-items: center;
  max-width: 520px;
  padding: 36px 20px;
  margin: 0 auto;
  text-align: center;
}

.workbench__complete-icon {
  width: 48px;
  height: 48px;
  margin-bottom: 14px;
  color: rgb(var(--v-theme-tertiary));
}

.workbench__complete .v-btn {
  margin-top: 20px;
}

@media (max-width: 720px) {
  .workbench {
    width: 100%;
    padding: 20px 16px 32px;
  }

  .workbench__step-content {
    grid-template-columns: 34px minmax(0, 1fr);
    gap: 12px;
  }

  .workbench__content-icon {
    width: 34px;
    height: 34px;
  }

  .workbench__step-content > :not(.workbench__content-icon):not(div:first-of-type) {
    grid-column: 1 / -1;
  }

  .workbench__summary div {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
