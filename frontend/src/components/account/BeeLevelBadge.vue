<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { BEE_TONE_CHIP, beeMark } from '@/account/beeLevels'

const props = defineProps<{ level: number }>()
const { t } = useI18n()

const mark = computed(() => beeMark(props.level))
const toneChip = computed(() => BEE_TONE_CHIP[mark.value.tone])
const levelName = computed(() => t(`account.beeLevel.${mark.value.level}`))
</script>

<template>
  <span class="cx-chip" :class="toneChip">
    <span aria-hidden="true">{{ mark.emblem }}</span>
    {{ levelName }} · Lv{{ mark.level }}
  </span>
</template>
