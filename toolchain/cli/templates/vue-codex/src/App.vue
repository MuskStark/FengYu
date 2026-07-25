<script setup lang="ts">
/**
 * {{pluginName}} — a FengYu plugin scaffolded by `fengyu plugin create`.
 *
 * This is the canonical Codex composition: the plugin shell, page header, file
 * picker, task table, and notification center around a realistic "import and
 * process spreadsheets" workflow. It consumes a {@link FengYuClient} via
 * {@link useFengYuClient}, which is provided at the app root by `main.ts`.
 *
 * The task list is seeded with deterministic rows so the initial view is stable.
 *
 * Plugin id: {{pluginId}}
 */
import { ref } from 'vue'
import {
  FyFilePicker,
  FyNotificationCenter,
  FyPageHeader,
  FyPluginShell,
  FyTaskTable,
  FyToolbar,
  useFengYuClient,
} from '@infinia/plugin-ui'
import type { FyTaskRow } from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'

const client = useFengYuClient()

/** Deterministic seed so the initial view is stable. */
const tasks = ref<FyTaskRow[]>([
  { id: 't-1', name: 'sales-2026.xlsx', status: 'success', detail: '1,024 rows' },
  { id: 't-2', name: 'contacts.xlsx', status: 'running', detail: 'Processing 42%' },
  { id: 't-3', name: 'inventory.csv', status: 'queued' },
  { id: 't-4', name: 'legacy-report.xls', status: 'cancelled', detail: 'Cancelled by user' },
])

const activeView = ref('tasks')
const selectedFile = ref<FileRef | null>(null)
const notifications = ref<InstanceType<typeof FyNotificationCenter> | null>(null)

async function onFile(file: FileRef | null): Promise<void> {
  selectedFile.value = file
  if (file) {
    // Surface a host notification; the notification center falls back to a
    // local queue if the host rejects it.
    await notifications.value?.notify(`Selected ${file.name}`)
  }
}

async function runImport(): Promise<void> {
  if (!selectedFile.value) {
    await notifications.value?.notify('Choose a file first')
    return
  }
  tasks.value.unshift({
    id: `t-${tasks.value.length + 1}`,
    name: selectedFile.value.name,
    status: 'queued',
  })
  await notifications.value?.notify(`Queued ${selectedFile.value.name} for import`)
}
</script>

<template>
  <FyPluginShell
    v-model="activeView"
    title="{{pluginName}}"
    :items="[
      { value: 'tasks', title: 'Tasks', icon: 'mdi-format-list-checks' },
      { value: 'sources', title: 'Sources', icon: 'mdi-folder-multiple-outline' },
    ]"
  >
    <div data-workbench class="pa-4">
      <FyPageHeader
        title="Import spreadsheets"
        description="Pick a source file, then queue it for processing."
      >
        <template #actions>
          <FyToolbar>
            <FyFilePicker
              label="Choose file"
              :extensions="['xlsx', 'csv']"
              :model-value="selectedFile"
              @update:model-value="onFile"
              @cancel="onFile(null)"
            />
            <v-btn
              color="primary"
              variant="flat"
              prepend-icon="mdi-play-outline"
              :disabled="!selectedFile"
              @click="runImport"
            >
              Run import
            </v-btn>
          </FyToolbar>
        </template>
      </FyPageHeader>

      <v-sheet v-if="selectedFile" variant="outlined" rounded class="pa-3 mb-4">
        <span class="text-body-2 opacity-70">Source:</span>
        <v-chip size="small" class="ml-2" label prepend-icon="mdi-file-outline">
          {{ selectedFile.name }}
        </v-chip>
      </v-sheet>

      <FyTaskTable :tasks="tasks" />

      <FyNotificationCenter ref="notifications" />
    </div>
  </FyPluginShell>
</template>
