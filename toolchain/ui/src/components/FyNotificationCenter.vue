<script setup lang="ts">
/**
 * Fallback notification center. Forwards {@link notify} to the host SDK via
 * {@link useFengYuNotify}; when the host rejects or throws, the message is
 * mirrored into a local queue rendered here as a Vuetify snackbar stack.
 *
 * Each visible message is announced with `aria-live="polite"` and carries a
 * close action (`data-action="dismiss"`). Call `notify(message)` from anywhere
 * in a plugin (e.g. via a template ref) to surface a user message.
 */
import { computed, inject } from 'vue'
import { FENGYU_CLIENT_KEY } from '../client'
import { useFengYuNotify } from '../composables/useFengYuNotify'
import { mdiClose } from '@mdi/js'
import FyIcon from './FyIcon.vue'

const client = inject(FENGYU_CLIENT_KEY, undefined)
const { notify, localMessages } = useFengYuNotify(client)
const active = computed(() => localMessages.value[0])

function dismiss(): void {
  localMessages.value.shift()
}

// Exposed so callers can drive notifications through a template ref, e.g.
// `(wrapper.vm as any).notify('...')`.
defineExpose({ notify, localMessages })
</script>

<template>
  <div class="fy-notification-center" aria-live="polite">
    <v-snackbar
      v-if="active"
      :key="active.id"
      :model-value="true"
      :class="`fy-notification-center__notice fy-notification-center__notice--${active.tone}`"
      attach
      contained
      location="top right"
      multi-line
      :timeout="active.timeout"
      @update:model-value="value => { if (!value) dismiss() }"
    >
      <span class="fy-notification-center__indicator" aria-hidden="true" />
      {{ active.message }}
      <template #actions>
        <v-btn :aria-label="'Dismiss notification'" data-action="dismiss" icon variant="text" @click="dismiss">
          <FyIcon :path="mdiClose" :size="18" />
        </v-btn>
      </template>
    </v-snackbar>
  </div>
</template>

<style scoped>
.fy-notification-center__indicator {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-inline-end: 9px;
  background: rgb(var(--v-theme-primary));
  border-radius: 50%;
  vertical-align: 1px;
}
.fy-notification-center__notice--success .fy-notification-center__indicator { background: rgb(var(--v-theme-tertiary)); }
.fy-notification-center__notice--warning .fy-notification-center__indicator,
.fy-notification-center__notice--error .fy-notification-center__indicator { background: rgb(var(--v-theme-error)); }
</style>
