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
import { useFengYuClient } from '../client'
import { useFengYuNotify } from '../composables/useFengYuNotify'

const client = useFengYuClient()
const { notify, localMessages } = useFengYuNotify(client)

function dismiss(index: number): void {
  localMessages.value.splice(index, 1)
}

// Exposed so callers can drive notifications through a template ref, e.g.
// `(wrapper.vm as any).notify('...')`.
defineExpose({ notify, localMessages })
</script>

<template>
  <div class="fy-notification-center" aria-live="polite">
    <v-snackbar
      v-for="(message, index) in localMessages"
      :key="`${index}-${message}`"
      :model-value="true"
      attach
      contained
      location="top right"
      multi-line
      timeout="-1"
    >
      {{ message }}
      <template #actions>
        <v-btn data-action="dismiss" icon="mdi-close" variant="text" @click="dismiss(index)" />
      </template>
    </v-snackbar>
  </div>
</template>
