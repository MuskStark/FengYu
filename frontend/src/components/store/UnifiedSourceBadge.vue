<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { StoreSourceType } from '@/api/types'

const props = defineProps<{ type: StoreSourceType }>()
const { t } = useI18n()
const color = computed(() => {
  switch (props.type) {
    case 'CLAUDE': return 'deep-orange'
    case 'CODEX': return 'teal'
    default: return 'primary'
  }
})
// Map the uppercase source type to the lowercase i18n key (source.fengyu / source.claude / source.codex).
const label = computed(() => t(`source.${props.type.toLowerCase()}`))
</script>

<template>
  <v-chip :color="color" size="x-small" label variant="tonal">{{ label }}</v-chip>
</template>
