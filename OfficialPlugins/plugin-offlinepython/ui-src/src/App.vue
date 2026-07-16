<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { FENGYU_CLIENT_KEY, FyDirectoryPicker, FyPageHeader, FyPluginShell, useFengYuNotify } from '@infinia/plugin-ui'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { useFengYuEnvironment } from './env'
import ConfigPanel from './panels/ConfigPanel.vue'
import BuildVerifyPanel from './panels/BuildVerifyPanel.vue'
import DeployPanel from './panels/DeployPanel.vue'
import DoctorPanel from './panels/DoctorPanel.vue'

const client = inject<FengYuClient>(FENGYU_CLIENT_KEY)!
const { locale, t } = useFengYuEnvironment()
const { notify } = useFengYuNotify(client)
const toast = (msg: string) => { notify(msg) }

// Shared project directory (writable FileRef granted by the host). All panels read it.
const project = ref<FileRef | null>(null)

const nav = computed(() => [
  { value: 'config', title: t('opb.nav.config') },
  { value: 'build', title: t('opb.nav.build') },
  { value: 'deploy', title: t('opb.nav.deploy') },
  { value: 'doctor', title: t('opb.nav.doctor') },
])
const active = ref('config')
</script>

<template>
  <FyPluginShell :title="t('opb.title')" :items="nav" v-model="active">
    <template #default>
      <FyPageHeader :title="t('opb.title')" :subtitle="project ? project.name : t('opb.project.empty')">
        <template #actions>
          <FyDirectoryPicker
            :label="t('opb.project.open')"
            :model-value="project"
            @update:model-value="project = $event"
          />
        </template>
      </FyPageHeader>

      <v-container fluid class="pa-4">
        <ConfigPanel v-if="active === 'config'" :client="client" :project="project" :locale="locale" @toast="toast" />
        <BuildVerifyPanel v-else-if="active === 'build'" :client="client" :project="project" :locale="locale" @toast="toast" />
        <DeployPanel v-else-if="active === 'deploy'" :client="client" :locale="locale" @toast="toast" />
        <DoctorPanel v-else :client="client" :locale="locale" @toast="toast" />
      </v-container>
    </template>
  </FyPluginShell>
</template>
