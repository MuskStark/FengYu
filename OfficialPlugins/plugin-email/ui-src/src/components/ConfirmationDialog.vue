<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Confirmation } from '../stores/compose'

defineProps<{ modelValue: boolean; confirmation?: Confirmation; busy?: boolean }>()
defineEmits<{ 'update:modelValue': [value: boolean]; approve: []; reject: [] }>()
const { t } = useI18n()
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="680" persistent @update:model-value="$emit('update:modelValue', $event)">
    <v-card>
      <v-card-title>{{ t('confirmation.title') }}</v-card-title>
      <v-card-text>
        <v-list density="compact">
          <v-list-item v-for="row in confirmation?.summary ?? []" :key="row.label" :title="row.label" :subtitle="row.value" />
        </v-list>
        <p class="hint">{{ t('confirmation.expires', { time: confirmation?.expiresAt ?? '' }) }}</p>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn data-testid="confirmation-reject" :disabled="busy" @click="$emit('reject')">{{ t('confirmation.reject') }}</v-btn>
        <v-btn data-testid="confirmation-approve" color="primary" :loading="busy" @click="$emit('approve')">{{ t('confirmation.approve') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
