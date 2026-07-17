<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { FENGYU_CLIENT_KEY, FyPluginShell, useFengYuNotify } from '@infinia/plugin-ui'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
// Inline SVG path data (tree-shakeable, no webfont) — the mdi webfont is not
// reliably bundled into the iframe and renders tofu squares in modern browsers.
import { mdiHammerWrench, mdiPackageVariantClosed, mdiStethoscope, mdiTuneVariant } from '@mdi/js'
import { useFengYuEnvironment } from './env'
import ConfigPanel from './panels/ConfigPanel.vue'
import BuildVerifyPanel from './panels/BuildVerifyPanel.vue'
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
  { value: 'build', title: t('opb.nav.build'), icon: mdiHammerWrench },
  { value: 'config', title: t('opb.nav.config'), icon: mdiTuneVariant },
  { value: 'deploy', title: t('opb.nav.deploy'), icon: mdiPackageVariantClosed },
  { value: 'doctor', title: t('opb.nav.doctor'), icon: mdiStethoscope },
])
const active = ref('build')
</script>

<template>
  <FyPluginShell :title="t('opb.title')" :items="nav" v-model="active">
    <template #default>
      <v-container fluid class="pa-4">
        <BuildVerifyPanel
          v-if="active === 'build'"
          :client="client"
          :project="project"
          :t="t"
          @update:project="project = $event"
          @toast="toast"
        />
        <ConfigPanel v-else-if="active === 'config'" :client="client" :project="project" :t="t" @toast="toast" />
        <DeployPanel v-else-if="active === 'deploy'" :client="client" :t="t" @toast="toast" />
        <DoctorPanel v-else :client="client" :t="t" @toast="toast" />
      </v-container>
    </template>
  </FyPluginShell>
</template>
