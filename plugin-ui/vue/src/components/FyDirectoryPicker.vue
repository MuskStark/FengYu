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
import { mdiFolderOpenOutline, mdiFolderPlusOutline, mdiSwapHorizontal } from '@mdi/js'
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
    <div v-if="modelValue" class="fy-picker__selection" aria-live="polite">
      <span class="fy-picker__icon" aria-hidden="true">
        <FyIcon :path="mode === 'output' ? mdiFolderPlusOutline : mdiFolderOpenOutline" :size="20" />
      </span>
      <span class="fy-picker__copy">
        <strong>{{ modelValue.name }}</strong>
        <small>{{ modelValue.access }} · {{ mode }}</small>
      </span>
      <v-btn
        icon
        variant="text"
        size="small"
        :loading="loading"
        :disabled="loading"
        :aria-label="label"
        :title="label"
        data-action="pick-directory"
        @click="pick"
      ><FyIcon :path="mdiSwapHorizontal" :size="18" /></v-btn>
    </div>
    <v-btn
      v-else
      color="primary"
      variant="tonal"
      :loading="loading"
      :disabled="loading"
      data-action="pick-directory"
      @click="pick"
    >
      <template #prepend>
        <FyIcon :path="mode === 'output' ? mdiFolderPlusOutline : mdiFolderOpenOutline" :size="18" />
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

<style scoped>
.fy-directory-picker { display: grid; gap: 10px; justify-items: start; }
.fy-picker__selection {
  display: flex;
  align-items: center;
  gap: 10px;
  width: min(100%, 460px);
  min-height: 52px;
  padding: 7px 8px 7px 10px;
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
}
.fy-picker__icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  color: rgb(var(--v-theme-secondary));
  background: rgb(var(--v-theme-surface-container-high));
  border-radius: 8px;
}
.fy-picker__copy { display: grid; min-width: 0; flex: 1 1 auto; }
.fy-picker__copy strong { overflow: hidden; font-size: 0.8125rem; font-weight: 590; text-overflow: ellipsis; white-space: nowrap; }
.fy-picker__copy small { color: rgb(var(--v-theme-secondary)); font-size: 0.6875rem; text-transform: capitalize; }
</style>
