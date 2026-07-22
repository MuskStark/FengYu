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
import { mdiAlertOutline, mdiHelpCircleOutline } from '@mdi/js'
import FyIcon from './FyIcon.vue'

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
    :retain-focus="false"
    :capture-focus="false"
    role="dialog"
    aria-modal="true"
    @update:model-value="($event as boolean) ? close(true) : cancel()"
    @keydown.esc.prevent="cancel"
  >
    <v-card class="fy-confirm-dialog">
      <v-card-text class="fy-confirm-dialog__body">
        <span class="fy-confirm-dialog__icon" :class="{ 'fy-confirm-dialog__icon--destructive': destructive }" aria-hidden="true">
          <FyIcon :path="destructive ? mdiAlertOutline : mdiHelpCircleOutline" :size="20" />
        </span>
        <div class="fy-confirm-dialog__copy">
          <h2>{{ title }}</h2>
          <p v-if="message">{{ message }}</p>
        </div>
      </v-card-text>
      <v-card-actions class="fy-confirm-dialog__actions">
        <v-spacer />
        <v-btn variant="text" data-action="cancel" @click="cancel">{{ cancelText }}</v-btn>
        <v-btn
          :color="destructive ? 'error' : 'primary'"
          variant="flat"
          data-action="confirm"
          @click="confirm"
        >
          {{ resolvedConfirmText }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.fy-confirm-dialog { overflow: hidden; }
.fy-confirm-dialog__body {
  display: flex;
  gap: 13px;
  padding: 22px 22px 10px !important;
}
.fy-confirm-dialog__icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  color: rgb(var(--v-theme-secondary));
  background: rgb(var(--v-theme-surface-container-high));
  border-radius: 10px;
}
.fy-confirm-dialog__icon--destructive { color: rgb(var(--v-theme-error)); background: rgb(var(--v-theme-error-container)); }
.fy-confirm-dialog__copy { min-width: 0; }
.fy-confirm-dialog__copy h2 { margin: 0; font-size: 1rem; font-weight: 630; line-height: 1.4; }
.fy-confirm-dialog__copy p { margin: 5px 0 0; color: rgb(var(--v-theme-secondary)); font-size: 0.8125rem; line-height: 1.5; }
.fy-confirm-dialog__actions { padding: 10px 14px 14px; }
</style>
