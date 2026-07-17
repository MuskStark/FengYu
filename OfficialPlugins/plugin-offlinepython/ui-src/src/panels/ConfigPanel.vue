<script setup lang="ts">
import { ref, watch } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyEmptyState } from '@infinia/plugin-ui'
import { mdiFolderOpenOutline } from '@mdi/js'
import { callChecked, field } from '../rpc'
import { configForm, type WorkerConfig } from '../configState'

type Translate = (key: string, ...args: (string | number)[]) => string

const props = defineProps<{
  client: FengYuClient
  project: FileRef | null
  t: Translate
}>()

const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

const requirements = ref('')
const pythonVersion = ref('3.12.10')
const platformsCsv = ref('win_amd64')
const onlyBinary = ref(true)
const recursive = ref(true)
const loading = ref(false)
let loadVersion = 0

function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : props.t('opb.common.error')
}

async function loadConfig() {
  const version = ++loadVersion
  const project = props.project
  const defaults = configForm()
  requirements.value = ''
  pythonVersion.value = defaults.pythonVersion
  platformsCsv.value = defaults.platformsCsv
  onlyBinary.value = defaults.onlyBinary
  recursive.value = defaults.recursive
  if (!project) return
  loading.value = true
  try {
    const req = await callChecked(props.client, 'requirements.get', { projectDir: project })
    if (version !== loadVersion) return
    requirements.value = String(field<string>(req, 'text') ?? '')
    const cfg = await callChecked(props.client, 'config.get', { projectDir: project, session: 'ui' })
    if (version !== loadVersion) return
    const form = configForm(field<WorkerConfig>(cfg, 'config'))
    pythonVersion.value = form.pythonVersion
    platformsCsv.value = form.platformsCsv
    onlyBinary.value = form.onlyBinary
    recursive.value = form.recursive
  } catch (error) {
    if (version === loadVersion) emit('toast', errorText(error))
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function save() {
  const project = props.project
  if (!project) return
  loading.value = true
  try {
    await callChecked(props.client, 'requirements.save', { projectDir: project, text: requirements.value })
    const config = {
      python: {
        version: pythonVersion.value,
        platforms: platformsCsv.value.split(',').map((s) => s.trim()).filter(Boolean),
      },
      download: { onlyBinary: onlyBinary.value, recursive: recursive.value },
    }
    await callChecked(props.client, 'config.save', { projectDir: project, session: 'ui', config })
    emit('toast', props.t('opb.config.saved'))
  } catch (error) {
    emit('toast', errorText(error))
  } finally {
    loading.value = false
  }
}

watch(() => props.project, loadConfig, { immediate: true })
</script>

<template>
  <FyEmptyState
    v-if="!project"
    :title="t('opb.project.empty')"
    :message="t('opb.config.openPrompt')"
    :icon="mdiFolderOpenOutline"
  />
  <v-card v-else flat border>
    <v-card-text>
      <div class="text-h6 mb-2">{{ t('opb.config.requirements') }}</div>
      <v-textarea v-model="requirements" rows="6" mono :placeholder="t('opb.config.requirementsPlaceholder')" hide-details class="mb-4" />

      <v-row>
        <v-col cols="12" sm="6">
          <v-text-field v-model="pythonVersion" :label="t('opb.config.pythonVersion')" :hint="t('opb.config.pythonHint')" persistent-hint />
        </v-col>
        <v-col cols="12" sm="6">
          <v-text-field v-model="platformsCsv" :label="t('opb.config.platforms')"
            :hint="t('opb.config.platformsHint')" persistent-hint />
        </v-col>
      </v-row>

      <div class="text-subtitle-2 mt-4 mb-2 text-medium-emphasis">{{ t('opb.config.download') }}</div>
      <v-switch v-model="onlyBinary" color="primary" :label="t('opb.config.onlyBinary')" hide-details />
      <v-switch v-model="recursive" color="primary" :label="t('opb.config.recursive')" hide-details />

      <div class="mt-4">
        <v-btn color="primary" :loading="loading" :disabled="loading" @click="save">{{ t('opb.config.save') }}</v-btn>
      </div>
    </v-card-text>
  </v-card>
</template>
