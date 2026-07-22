<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { FENGYU_CLIENT_KEY, FyPluginShell, useFengYuNotify } from '@infinia/plugin-ui'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
// Inline SVG path data (tree-shakeable, no webfont) — the mdi webfont is not
// reliably bundled into the iframe and renders tofu squares in modern browsers.
import { mdiHammerWrench, mdiPackageVariantClosed, mdiStethoscope } from '@mdi/js'
import { useFengYuEnvironment } from './env'
import ProjectPanel from './panels/ProjectPanel.vue'
import DeployPanel from './panels/DeployPanel.vue'
import DoctorPanel from './panels/DoctorPanel.vue'

const client = inject<FengYuClient>(FENGYU_CLIENT_KEY)!
const { t } = useFengYuEnvironment()
const { notify } = useFengYuNotify(client)
const toast = (msg: string) => { notify(msg) }

// Shared project directory (writable FileRef granted by the host). Selected from
// Build & Verify and read by every other panel — only shared state lives here.
const project = ref<FileRef | null>(null)

const nav = computed(() => [
  { value: 'project', title: t('opb.nav.project'), icon: mdiHammerWrench },
  { value: 'deploy', title: t('opb.nav.deploy'), icon: mdiPackageVariantClosed },
  { value: 'doctor', title: t('opb.nav.doctor'), icon: mdiStethoscope },
])
const active = ref('project')
</script>

<template>
  <FyPluginShell :title="t('opb.title')" :items="nav" v-model="active">
    <template #default>
      <main class="opb-page">
        <ProjectPanel
          v-if="active === 'project'"
          :client="client"
          :project="project"
          :t="t"
          @update:project="project = $event"
          @toast="toast"
        />
        <DeployPanel v-else-if="active === 'deploy'" :client="client" :t="t" @toast="toast" />
        <DoctorPanel v-else :client="client" :t="t" @toast="toast" />
      </main>
    </template>
  </FyPluginShell>
</template>

<style>
.opb-page {
  width: min(100%, 1080px);
  min-width: 0;
  padding: 28px 32px 48px;
  margin: 0 auto;
}

.opb-surface {
  overflow: hidden;
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-lg, 14px);
}

.opb-surface__section {
  padding: 20px;
}

.opb-surface__section + .opb-surface__section {
  border-top: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
}

.opb-section-heading {
  margin: 0 0 4px;
  font-size: 0.875rem;
  font-weight: 620;
}

.opb-section-copy {
  margin: 0 0 16px;
  color: rgb(var(--v-theme-secondary));
  font-size: 0.8125rem;
}

.opb-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.opb-actions--split {
  justify-content: space-between;
}

.opb-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 26px;
  padding: 3px 9px;
  color: rgb(var(--v-theme-secondary));
  background: rgb(var(--v-theme-surface-container-high));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 560;
}

.opb-status::before {
  width: 7px;
  height: 7px;
  content: '';
  background: currentColor;
  border-radius: 50%;
}

.opb-status--running { color: rgb(var(--v-theme-on-surface)); }
.opb-status--success { color: rgb(var(--v-theme-tertiary)); }
.opb-status--error { color: rgb(var(--v-theme-error)); }

.opb-log {
  min-height: 180px;
  max-height: 360px;
  padding: 14px 16px;
  margin: 0;
  overflow: auto;
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-background));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
  font-family: var(--fengyu-font-mono);
  font-size: 0.75rem;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.opb-segment {
  display: inline-flex;
  gap: 3px;
  padding: 3px;
  background: rgb(var(--v-theme-surface-container-high));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
}

.opb-segment button {
  min-height: 32px;
  padding: 0 12px;
  color: rgb(var(--v-theme-secondary));
  background: transparent;
  border: 0;
  border-radius: 7px;
  font: inherit;
  font-size: 0.8125rem;
  cursor: pointer;
}

.opb-segment button[aria-pressed='true'] {
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 1px 2px rgba(var(--v-theme-background), 0.22);
}

.opb-table-scroll {
  overflow-x: auto;
  border-radius: var(--fy-radius-md, 10px);
}

@media (max-width: 720px) {
  .opb-page {
    width: 100%;
    padding: 20px 16px 36px;
  }

  .opb-surface__section {
    padding: 16px;
  }

  .opb-actions--split {
    align-items: stretch;
    flex-direction: column;
  }

  .opb-segment {
    display: grid;
    grid-template-columns: 1fr 1fr;
    width: 100%;
  }
}
</style>
