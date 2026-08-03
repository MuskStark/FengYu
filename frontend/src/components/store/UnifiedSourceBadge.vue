<script setup lang="ts">
import { computed } from 'vue'
import type { StoreSourceType } from '@/api/types'

const props = defineProps<{ type: StoreSourceType }>()
const color = computed(() => {
  switch (props.type) {
    case 'CLAUDE': return 'deep-orange'
    case 'CODEX': return 'teal'
    default: return 'primary'
  }
})
const label = computed(() => props.type)
</script>

<template>
  <v-chip :color="color" size="x-small" label variant="tonal">{{ label }}</v-chip>
</template>
