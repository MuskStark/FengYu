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
import { useFengYuClient } from '../client'
import { useFengYuPick } from '../composables/useFengYuPick'
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
    <v-btn
      color="primary"
      variant="tonal"
      :loading="loading"
      :disabled="loading"
      data-action="pick-file"
      :prepend-icon="extensions.length ? 'mdi-file-document-outline' : 'mdi-file-outline'"
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
      title="Could not open file"
      :message="errorMessage"
      @retry="pick"
    />
  </div>
</template>
