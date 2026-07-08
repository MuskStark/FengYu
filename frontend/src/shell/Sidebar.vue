<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useThemeStore } from '@/stores/theme'
import { useNavStore, type NavCategory } from '@/stores/nav'

const settings = useSettingsStore()
const theme = useThemeStore()
const nav = useNavStore()
const router = useRouter()

const collapsed = computed(() => settings.sidebarCollapsed)

interface CatItem {
  key: NavCategory
  label: string
  icon: string
}
const categories: CatItem[] = [
  { key: 'all', label: 'All Tools', icon: '▦' },
  { key: 'text', label: 'Text', icon: '¶' },
  { key: 'image', label: 'Image', icon: '▩' },
  { key: 'dev', label: 'Dev', icon: '⚙' },
  { key: 'net', label: 'Net', icon: '☍' },
  { key: 'other', label: 'Other', icon: '◇' },
  { key: 'favorites', label: 'Favorites', icon: '★' },
]

function pickCategory(c: NavCategory) {
  nav.setCategory(c)
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
      <button class="collapse-btn" :title="collapsed ? 'Expand' : 'Collapse'" @click="toggleCollapsed">
        {{ collapsed ? '»' : '«' }}
      </button>
    </div>

    <nav class="section">
      <div v-if="!collapsed" class="section-title">CATEGORIES</div>
      <button
        v-for="c in categories"
        :key="c.key"
        class="nav-item"
        :class="{ active: nav.category === c.key }"
        :title="c.label"
        @click="pickCategory(c.key)"
      >
        <span class="nav-icon">{{ c.icon }}</span>
        <span v-if="!collapsed" class="nav-label">{{ c.label }}</span>
      </button>
    </nav>

    <div class="spacer" />

    <nav class="section footer">
      <button class="nav-item" title="AI Chat" @click="router.push('/ai')">
        <span class="nav-icon">✦</span>
        <span v-if="!collapsed" class="nav-label">AI Chat</span>
      </button>
      <button class="nav-item" title="Settings" @click="router.push('/settings')">
        <span class="nav-icon">⚙</span>
        <span v-if="!collapsed" class="nav-label">Settings</span>
      </button>
      <button class="nav-item" :title="`Theme: ${theme.theme}`" @click="theme.toggle()">
        <span class="nav-icon">{{ theme.theme === 'dark' ? '☾' : '☀' }}</span>
        <span v-if="!collapsed" class="nav-label">Theme</span>
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
