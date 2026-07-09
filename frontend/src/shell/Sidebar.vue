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

const collapsed = computed(() => settings.sidebarCollapsed)

/**
 * Reactive nav built from the backend category descriptors, bookended by the
 * "all" and "favorites" pseudo-categories. Keys are the lowercase category ids
 * (matching /api/plugin-categories); ToolGrid normalises plugin.category to
 * lowercase at the filter boundary.
 */
interface NavItem {
  key: string
  labelKey: string
  icon: string
}
const navItems = computed<NavItem[]>(() => [
  { key: 'all', labelKey: 'sidebar.all', icon: '▦' },
  ...cats.categories.map((c) => ({ key: c.id, labelKey: c.labelKey, icon: c.icon })),
  { key: 'favorites', labelKey: 'sidebar.favorites', icon: '★' },
])

onMounted(() => {
  if (!cats.loaded) void cats.load()
})

function pickCategory(key: string) {
  nav.setCategory(key)
  router.push('/')
}

function toggleCollapsed() {
  void settings.setSidebarCollapsed(!collapsed.value)
}
</script>

<template>
  <aside class="sidebar" :class="{ collapsed }">
    <div class="brand">
      <span class="logo">ZF</span>
      <span v-if="!collapsed" class="brand-name">ZhiFlow</span>
      <button
        class="collapse-btn"
        :title="collapsed ? $t('sidebar.expand') : $t('sidebar.collapse')"
        @click="toggleCollapsed"
      >
        {{ collapsed ? '»' : '«' }}
      </button>
    </div>

    <nav class="section">
      <div v-if="!collapsed" class="section-title">{{ $t('sidebar.categories') }}</div>
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-item"
        :class="{ active: nav.category === item.key }"
        :title="$t(item.labelKey)"
        @click="pickCategory(item.key)"
      >
        <span class="nav-icon">{{ item.icon }}</span>
        <span v-if="!collapsed" class="nav-label">{{ $t(item.labelKey) }}</span>
      </button>
    </nav>

    <div class="spacer" />

    <nav class="section footer">
      <button class="nav-item" :title="$t('sidebar.aiChat')" @click="router.push('/ai')">
        <span class="nav-icon">✦</span>
        <span v-if="!collapsed" class="nav-label">{{ $t('sidebar.aiChat') }}</span>
      </button>
      <button class="nav-item" :title="$t('sidebar.agent')" @click="router.push('/agent')">
        <span class="nav-icon">✪</span>
        <span v-if="!collapsed" class="nav-label">{{ $t('sidebar.agent') }}</span>
      </button>
      <button class="nav-item" :title="$t('sidebar.settings')" @click="router.push('/settings')">
        <span class="nav-icon">⚙</span>
        <span v-if="!collapsed" class="nav-label">{{ $t('sidebar.settings') }}</span>
      </button>
      <button class="nav-item" @click="theme.toggle()">
        <span class="nav-icon">{{ theme.theme === 'dark' ? '☾' : '☀' }}</span>
        <span v-if="!collapsed" class="nav-label">{{ $t('sidebar.theme') }}</span>
      </button>
    </nav>
  </aside>
</template>

<style scoped>
.sidebar {
  grid-area: sidebar;
  display: flex;
  flex-direction: column;
  width: 220px;
  background: var(--sk-bg-elevated);
  border-right: 1px solid var(--sk-border);
  padding: 10px 8px;
  transition: width 0.15s ease;
}
.sidebar.collapsed {
  width: 56px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px 12px;
}
.logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--sk-accent);
  color: #fff;
  font-weight: 700;
  font-size: 12px;
}
.brand-name {
  font-weight: 600;
  color: var(--sk-text);
  font-size: 14px;
}
.collapse-btn {
  margin-left: auto;
  background: transparent;
  border: 0;
  color: var(--sk-text-secondary);
  cursor: pointer;
  font-size: 14px;
  padding: 2px 6px;
  border-radius: 4px;
}
.collapse-btn:hover {
  background: var(--sk-bg-hover);
  color: var(--sk-text);
}
.section {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.section-title {
  padding: 8px 10px 4px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: transparent;
  border: 0;
  border-radius: 6px;
  color: var(--sk-text-secondary);
  cursor: pointer;
  font-size: 13px;
  text-align: left;
  white-space: nowrap;
}
.nav-item:hover {
  background: var(--sk-bg-hover);
  color: var(--sk-text);
}
.nav-item.active {
  background: var(--sk-accent-soft);
  color: var(--sk-accent);
}
.nav-icon {
  width: 18px;
  text-align: center;
}
.spacer {
  flex: 1;
}
.footer {
  border-top: 1px solid var(--sk-border);
  padding-top: 8px;
}
</style>
