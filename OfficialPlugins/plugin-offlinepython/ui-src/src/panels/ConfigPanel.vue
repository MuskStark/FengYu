<script setup lang="ts">
import { ref, watch } from 'vue'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { FyEmptyState } from '@infinia/plugin-ui'
import { call, field, refPath } from '../rpc'

const props = defineProps<{
  client: FengYuClient
  project: FileRef | null
  locale: string
}>()

const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

const requirements = ref('')
const pythonVersion = ref('3.12.10')
const platformsCsv = ref('win_amd64')
const onlyBinary = ref(true)
const recursive = ref(true)
const loading = ref(false)
const initialised = ref(false)

async function loadConfig() {
  if (!props.project) return
  const dir = refPath(props.project)!
  loading.value = true
  try {
    const req = await call(props.client, 'requirements.get', { projectDir: dir })
    requirements.value = req.success ? String(field<string>(req, 'text') ?? '') : ''
    const cfg = await call(props.client, 'config.get', { projectDir: dir, session: 'ui' })
    const config = cfg.success ? field<{
      python?: { version?: string; platforms?: string[] }
      download?: { onlyBinary?: boolean; recursive?: boolean }
    }>(cfg, 'config') : undefined
    if (config) {
      pythonVersion.value = config.python?.version ?? pythonVersion.value
      platformsCsv.value = (config.python?.platforms ?? []).join(', ') || platformsCsv.value
      onlyBinary.value = config.download?.onlyBinary ?? onlyBinary.value
      recursive.value = config.download?.recursive ?? recursive.value
    }
    initialised.value = true
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!props.project) return
  const dir = refPath(props.project)!
  loading.value = true
  try {
    await call(props.client, 'requirements.save', { projectDir: dir, text: requirements.value })
    const config = {
      python: {
        version: pythonVersion.value,
        platforms: platformsCsv.value.split(',').map((s) => s.trim()).filter(Boolean),
      },
      download: { onlyBinary: onlyBinary.value, recursive: recursive.value },
    }
    const res = await call(props.client, 'config.save', { projectDir: dir, session: 'ui', config })
    emit('toast', res.success ? 'Config saved' : res.summary)
  } finally {
    loading.value = false
  }
}

watch(() => props.project, loadConfig, { immediate: true })
</script>

<template>
  <FyEmptyState v-if="!project" title="Open a project" description="Choose a project folder to configure." />
  <v-card v-else flat border>
    <v-card-text>
      <div class="text-h6 mb-2">requirements.txt</div>
      <v-textarea v-model="requirements" rows="6" mono placeholder="# numpy==1.26.4" hide-details class="mb-4" />

      <v-row>
        <v-col cols="12" sm="6">
          <v-text-field v-model="pythonVersion" label="Python version" hint="e.g. 3.12.10" persistent-hint />
        </v-col>
        <v-col cols="12" sm="6">
          <v-text-field v-model="platformsCsv" label="Target platforms (comma-separated)"
            hint="e.g. win_amd64, manylinux2014_x86_64" persistent-hint />
        </v-col>
      </v-row>

      <v-switch v-model="onlyBinary" color="primary" label="Only binary (wheels)" hide-details />
      <v-switch v-model="recursive" color="primary" label="Resolve dependencies recursively" hide-details />

      <div class="mt-4">
        <v-btn color="primary" :loading="loading" @click="save">Save config</v-btn>
      </div>
    </v-card-text>
  </v-card>
</template>
