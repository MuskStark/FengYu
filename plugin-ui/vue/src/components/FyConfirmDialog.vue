<script setup lang="ts">
/**
 * FyConfirmDialog — a confirmation modal that requires an explicit choice.
 *
 * - `modelValue` (v-model, boolean) controls visibility.
 * - `destructive` styles the confirm button as an error action and labels it
 *   textually ("Delete") so the destructive intent is conveyed without relying
 *   on color alone.
 * - Emits `confirm` (`data-action="confirm"`), `cancel`
 *   (`data-action="cancel"`), and `update:modelValue`. Both actions close the
 *   dialog. Closing via the overlay or Esc emits `cancel`.
 * - Because this dialog is `modelValue`-controlled with NO activator slot,
 *   Vuetify's activator-based focus-restore does not apply. The component
 *   therefore captures the opener element itself when it opens and restores
 *   focus to it on close, so the "return focus to the opener" contract holds
 *   regardless of Vuetify internals.
 */
import { computed, nextTick, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    title: string
    message?: string
    destructive?: boolean
    /** Text for the confirm button. Defaults to "Delete" when destructive. */
    confirmText?: string
    /** Text for the cancel button. */
    cancelText?: string
  }>(),
  {
    modelValue: false,
    message: '',
    destructive: false,
    confirmText: undefined,
    cancelText: 'Cancel',
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'confirm'): void
  (event: 'cancel'): void
}>()

const resolvedConfirmText = computed(() => props.confirmText ?? (props.destructive ? 'Delete' : 'Confirm'))

/**
 * The element that held focus when the dialog opened — the "opener". Captured
 * only at the moment of opening (false → true) so that subsequent prop updates
 * while open don't overwrite it. Restored on close (true → false).
 */
const opener = ref<HTMLElement | null>(null)

watch(
  () => props.modelValue,
  (now, prev) => {
    if (now && !prev) {
      // Opening: remember whoever currently holds focus.
      opener.value = (document.activeElement as HTMLElement | null) ?? null
    } else if (!now && prev) {
      // Closing: hand focus back to the opener on the next tick, after the
      // dialog has finished tearing down.
      const target = opener.value
      opener.value = null
      if (target) {
        void nextTick(() => target.focus?.())
      }
    }
  },
)

function close(value: boolean): void {
  emit('update:modelValue', value)
}

function confirm(): void {
  emit('confirm')
  close(false)
}

function cancel(): void {
  emit('cancel')
  close(false)
}
</script>

<template>
  <v-dialog
    :model-value="modelValue"
    max-width="480"
    persistent
    role="dialog"
    aria-modal="true"
    @update:model-value="($event as boolean) ? close(true) : cancel()"
    @keydown.esc.prevent="cancel"
  >
    <v-card :title="title">
      <v-card-text v-if="message">{{ message }}</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" data-action="cancel" @click="cancel">{{ cancelText }}</v-btn>
        <v-btn
          :color="destructive ? 'error' : 'primary'"
          :variant="destructive ? 'flat' : 'tonal'"
          data-action="confirm"
          @click="confirm"
        >
          {{ resolvedConfirmText }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
