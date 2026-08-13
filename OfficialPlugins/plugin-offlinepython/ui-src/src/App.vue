<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { FENGYU_CLIENT_KEY, FyPluginPage, FyPluginShell, useFengYuNotify } from '@infinia/plugin-ui'
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
      <FyPluginPage :max-width="1080">
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
      </FyPluginPage>
  </FyPluginShell>
</template>

<style>
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

@media (max-width: 720px) {
  .opb-segment {
    display: grid;
    grid-template-columns: 1fr 1fr;
    width: 100%;
  }
}

@container fy-plugin-page (max-width: 720px) {
  .opb-segment {
    display: grid;
    grid-template-columns: 1fr 1fr;
    width: 100%;
  }
}
</style>
