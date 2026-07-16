<script setup lang="ts">
/**
 * SDK-backed directory picker. Delegates to {@link FengYuClient.files.inputDirectory}
 * or {@link FengYuClient.files.outputDirectory} based on the `mode` prop.
 *
 * Behavioral contract mirrors {@link FyFilePicker}: cancellation (`null`) emits
 * `cancel` and no alert; permission denials render {@link FyPermissionNotice}
 * without auto-retry; other errors render {@link FyErrorState} with retry.
 * Concurrent clicks are guarded by `loading`.
 */
import type { FileRef } from '@infinia/plugin-sdk'
import { useFengYuClient } from '../client'
import { useFengYuPick } from '../composables/useFengYuPick'
import FyPermissionNotice from './FyPermissionNotice.vue'
import FyErrorState from './FyErrorState.vue'

const props = withDefaults(
  defineProps<{
    /** v-model: the selected directory, or `null` when nothing is chosen. */
    modelValue?: FileRef | null
    /** Which host SDK method to call: input vs. output directory picker. */
    mode?: 'input' | 'output'
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
  request: () => (props.mode === 'input' ? client.files.inputDirectory() : client.files.outputDirectory()),
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
      :prepend-icon="mode === 'input' ? 'mdi-folder-open-outline' : 'mdi-folder-plus-outline'"
      @click="pick"
    >
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
