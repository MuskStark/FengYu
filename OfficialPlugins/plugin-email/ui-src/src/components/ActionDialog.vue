<script setup lang="ts">
import { useI18n } from 'vue-i18n'

withDefaults(defineProps<{
  modelValue: boolean
  title: string
  message: string
  confirmText?: string
  destructive?: boolean
}>(), { confirmText: undefined, destructive: false })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; confirm: [] }>()
const { t } = useI18n()

function close(): void { emit('update:modelValue', false) }
function confirm(): void { emit('confirm'); close() }
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="440" @update:model-value="$emit('update:modelValue', $event)">
    <v-card class="codex-dialog">
      <v-card-title>{{ title }}</v-card-title>
      <v-card-text class="codex-dialog__message">{{ message }}</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn data-testid="action-dialog-cancel" variant="tonal" @click="close">{{ t('common.cancel') }}</v-btn>
        <v-btn data-testid="action-dialog-confirm" :color="destructive ? 'error' : 'primary'" @click="confirm">
          {{ confirmText ?? t('common.confirm') }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
