<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { FENGYU_CLIENT_KEY, FyPluginShell, useFengYuNotify } from '@infinia/plugin-ui'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
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
  { value: 'build', title: t('opb.nav.build'), icon: 'mdi-hammer-wrench' },
  { value: 'config', title: t('opb.nav.config'), icon: 'mdi-tune-variant' },
  { value: 'deploy', title: t('opb.nav.deploy'), icon: 'mdi-package-variant-closed' },
  { value: 'doctor', title: t('opb.nav.doctor'), icon: 'mdi-stethoscope' },
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
