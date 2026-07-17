<script setup lang="ts">
/**
 * SDK-backed directory picker. Delegates to {@link FengYuClient.files.inputDirectory}
 * {@link FengYuClient.files.workspaceDirectory}, or
 * {@link FengYuClient.files.outputDirectory} based on the `mode` prop.
 *
 * Behavioral contract mirrors {@link FyFilePicker}: cancellation (`null`) emits
 * `cancel` and no alert; permission denials render {@link FyPermissionNotice}
 * without auto-retry; other errors render {@link FyErrorState} with retry.
 * Concurrent clicks are guarded by `loading`.
 */
import type { FileRef } from '@infinia/plugin-sdk'
import { mdiFolderOpenOutline, mdiFolderPlusOutline } from '@mdi/js'
import { useFengYuClient } from '../client'
import { useFengYuPick } from '../composables/useFengYuPick'
import FyIcon from './FyIcon.vue'
import FyPermissionNotice from './FyPermissionNotice.vue'
import FyErrorState from './FyErrorState.vue'

const props = withDefaults(
  defineProps<{
    /** v-model: the selected directory, or `null` when nothing is chosen. */
    modelValue?: FileRef | null
    /** Which host SDK method to call: input vs. output directory picker. */
    mode?: 'input' | 'workspace' | 'output'
    /** Button label. */
    label?: string
  }>(),
  {
    modelValue: null,
    mode: 'input',
    label: 'Choose folder',
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: FileRef | null): void
  (event: 'cancel'): void
  (event: 'error', error: Error): void
}>()

const client = useFengYuClient()

const { loading, errorMessage, permissionDenied, pick } = useFengYuPick({
  request: () => {
    if (props.mode === 'workspace') return client.files.workspaceDirectory()
    return props.mode === 'input' ? client.files.inputDirectory() : client.files.outputDirectory()
  },
  emit,
})
</script>

<template>
  <div class="fy-directory-picker">
    <v-btn
      color="primary"
      variant="tonal"
      :loading="loading"
      :disabled="loading"
      data-action="pick-directory"
      @click="pick"
    >
      <template #prepend>
        <FyIcon :path="mode === 'output' ? mdiFolderPlusOutline : mdiFolderOpenOutline" :size="20" />
      </template>
      {{ label }}
    </v-btn>

    <FyPermissionNotice
      v-if="errorMessage && permissionDenied"
      :message="errorMessage"
    />
    <FyErrorState
      v-else-if="errorMessage"
      title="Could not open folder"
      :message="errorMessage"
      @retry="pick"
    />
  </div>
</template>
