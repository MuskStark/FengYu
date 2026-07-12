<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useThemeStore } from '@/stores/theme'
import { useNavStore } from '@/stores/nav'
import { useCategoriesStore } from '@/stores/categories'

const settings = useSettingsStore()
const theme = useThemeStore()
const nav = useNavStore()
const cats = useCategoriesStore()
const router = useRouter()

const rail = computed(() => settings.sidebarCollapsed)

interface NavItem {
  key: string
  labelKey: string
  icon: string
}
const navItems = computed<NavItem[]>(() => [
  { key: 'all', labelKey: 'sidebar.all', icon: 'mdi-view-grid' },
  ...cats.categories.map((c) => ({ key: c.id, labelKey: c.labelKey, icon: c.icon || 'mdi-folder' })),
  { key: 'favorites', labelKey: 'sidebar.favorites', icon: 'mdi-star' },
])

onMounted(() => {
  if (!cats.loaded) void cats.load()
})

function pickCategory(key: string) {
  nav.setCategory(key)
  router.push('/')
}
</script>

<template>
  <v-navigation-drawer :rail="rail" permanent width="220" rail-width="64">
    <div
      class="d-flex align-center py-3"
      :class="rail ? 'justify-center' : 'px-3'"
    >
      <v-avatar color="primary" size="32" rounded="lg">
        <v-icon icon="mdi-hexagon-multiple-outline" />
      </v-avatar>
      <span v-if="!rail" class="ml-3 font-weight-medium">ZhiFlow</span>
      <v-spacer v-if="!rail" />
      <v-btn
        v-if="!rail"
        icon="mdi-chevron-left"
        size="small"
        variant="text"
        :title="$t('sidebar.collapse')"
        @click="settings.setSidebarCollapsed(true)"
      />
    </div>

    <v-list density="compact" nav>
      <v-list-subheader v-if="!rail">{{ $t('sidebar.categories') }}</v-list-subheader>
      <v-list-item
        v-for="item in navItems"
        :key="item.key"
        :active="nav.category === item.key"
        :prepend-icon="item.icon"
        :title="$t(item.labelKey)"
        :value="item.key"
        @click="pickCategory(item.key)"
      />
    </v-list>

    <template #append>
      <v-list v-if="rail" density="compact" nav class="pb-2">
        <v-list-item
          prepend-icon="mdi-chevron-right"
          :title="$t('sidebar.expand')"
          @click="settings.setSidebarCollapsed(false)"
        />
      </v-list>
      <v-list density="compact" nav>
        <v-list-item prepend-icon="mdi-chat-outline" :title="$t('sidebar.aiChat')" @click="router.push('/ai')" />
        <v-list-item prepend-icon="mdi-robot-outline" :title="$t('sidebar.agent')" @click="router.push('/agent')" />
        <v-list-item prepend-icon="mdi-cog-outline" :title="$t('sidebar.settings')" @click="router.push('/settings')" />
        <v-list-item
          :prepend-icon="theme.theme === 'dark' ? 'mdi-weather-night' : 'mdi-weather-sunny'"
          :title="$t('sidebar.theme')"
          @click="theme.toggle()"
        />
      </v-list>
    </template>
  </v-navigation-drawer>
</template>
