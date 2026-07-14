<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useNavigationStore } from '../stores/navigation'
import EmailIcon from './EmailIcon.vue'

const navigation = useNavigationStore()
const { t } = useI18n()
</script>

<template>
  <nav class="task-rail" aria-label="Email Center">
    <div class="task-rail__brand">
      <span class="task-rail__brand-mark" aria-hidden="true"></span>
      Email Center
    </div>
    <button
      v-for="item in navigation.items"
      :key="item.id"
      type="button"
      class="task-rail__item"
      :class="{ 'task-rail__item--active': navigation.active === item.id, 'task-rail__item--bottom': item.bottom }"
      :aria-current="navigation.active === item.id ? 'page' : undefined"
      @click="navigation.active = item.id"
    >
      <EmailIcon :path="item.icon" />
      <span>{{ t(item.labelKey) }}</span>
    </button>
  </nav>
</template>
