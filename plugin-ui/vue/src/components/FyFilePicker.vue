<script setup lang="ts">
/**
 * SDK-backed file picker. Wraps {@link FengYuClient.files.open} so a plugin can
 * request a host file through a single button.
 *
 * Behavioral contract:
 * - Emits `update:modelValue` (the `FileRef`, or `null`) for v-model.
 * - Cancellation — the host resolving `null` — emits `cancel` and renders NO
 *   alert region; cancellation is a normal empty result, never an error.
 * - Rejections whose message indicates a permission denial render
 *   {@link FyPermissionNotice} and do NOT auto-retry.
 * - All other rejections render {@link FyErrorState}; its retry action re-runs
 *   the pick.
 * - A `loading` guard blocks concurrent picks.
 */
import type { FileFilter, FileRef } from '@infinia/plugin-sdk'
import { mdiFileDocumentOutline, mdiFileOutline, mdiSwapHorizontal } from '@mdi/js'
import { useFengYuClient } from '../client'
import { useFengYuPick } from '../composables/useFengYuPick'
import FyIcon from './FyIcon.vue'
import FyPermissionNotice from './FyPermissionNotice.vue'
import FyErrorState from './FyErrorState.vue'

const props = withDefaults(
  defineProps<{
    /** v-model: the selected file, or `null` when nothing is chosen. */
    modelValue?: FileRef | null
    /** Extension allowlist forwarded to the host file dialog. */
    extensions?: string[]
    /** Named extension filters forwarded to the host file dialog. */
    filters?: FileFilter[]
    /** Button label. */
    label?: string
  }>(),
  {
    modelValue: null,
    extensions: () => [],
    filters: () => [],
    label: 'Choose file',
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: FileRef | null): void
  (event: 'cancel'): void
  (event: 'error', error: Error): void
}>()

const client = useFengYuClient()

const { loading, errorMessage, permissionDenied, pick } = useFengYuPick({
  request: () => client.files.open({ extensions: props.extensions, filters: props.filters }),
  emit,
})
</script>

<template>
  <div class="fy-file-picker">
    <div v-if="modelValue" class="fy-picker__selection" aria-live="polite">
      <span class="fy-picker__icon" aria-hidden="true">
        <FyIcon :path="mdiFileDocumentOutline" :size="20" />
      </span>
      <span class="fy-picker__copy">
        <strong>{{ modelValue.name }}</strong>
        <small>{{ modelValue.access }}<template v-if="modelValue.size"> · {{ modelValue.size.toLocaleString() }} B</template></small>
      </span>
      <v-btn
        icon
        variant="text"
        size="small"
        :loading="loading"
        :disabled="loading"
        :aria-label="label"
        :title="label"
        data-action="pick-file"
        @click="pick"
      ><FyIcon :path="mdiSwapHorizontal" :size="18" /></v-btn>
    </div>
    <v-btn
      v-else
      color="primary"
      variant="tonal"
      :loading="loading"
      :disabled="loading"
      data-action="pick-file"
      @click="pick"
    >
      <template #prepend>
        <FyIcon :path="extensions.length ? mdiFileDocumentOutline : mdiFileOutline" :size="18" />
      </template>
      {{ label }}
    </v-btn>

    <FyPermissionNotice
      v-if="errorMessage && permissionDenied"
      :message="errorMessage"
    />
    <FyErrorState
      v-else-if="errorMessage"
      title="Could not open file"
      :message="errorMessage"
      @retry="pick"
    />
  </div>
</template>

<style scoped>
.fy-file-picker { display: grid; gap: 10px; justify-items: start; }
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
