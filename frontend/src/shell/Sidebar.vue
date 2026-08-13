<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDisplay } from 'vuetify'
import { useSettingsStore } from '@/stores/settings'
import { useAiSessionStore } from '@/stores/aiSession'
import { useAccountStore } from '@/stores/account'

const settings = useSettingsStore()
const ai = useAiSessionStore()
const account = useAccountStore()
const router = useRouter()
const route = useRoute()

const { width } = useDisplay()
const autoRail = computed(() => width.value < 900)
const rail = computed(() => settings.sidebarCollapsed || autoRail.value)
const accountMenuOpen = ref(false)
const accountArea = ref<HTMLElement | null>(null)

const primaryNav = [
  { key: 'chat', to: '/', labelKey: 'sidebar.aiChat', icon: 'mdi-message-outline' },
  { key: 'tools', to: '/tools', labelKey: 'sidebar.all', icon: 'mdi-view-grid-outline' },
  { key: 'plugins', to: '/plugins', labelKey: 'sidebar.plugins', icon: 'mdi-shopping-outline' },
  { key: 'agent', to: '/agent', labelKey: 'sidebar.agent', icon: 'mdi-source-branch' },
]

onMounted(() => {
  void ai.loadHistory()
  void account.load().catch(() => {
    // Keep the local shell usable if a future remote account provider is offline.
  })
  document.addEventListener('pointerdown', closeAccountMenuOnOutsideClick)
  document.addEventListener('keydown', closeAccountMenuOnEscape)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeAccountMenuOnOutsideClick)
  document.removeEventListener('keydown', closeAccountMenuOnEscape)
})

function startChat() {
  ai.newConversation()
  if (route.name !== 'ai') void router.push('/')
}

function openConversation(id: number) {
  void ai.select(id)
  if (route.name !== 'ai') void router.push('/')
}

function setCollapsed(collapsed: boolean) {
  accountMenuOpen.value = false
  void settings.setSidebarCollapsed(collapsed)
}

function toggleAccountMenu() {
  accountMenuOpen.value = !accountMenuOpen.value
}

function navigateFromAccountMenu(path: string) {
  accountMenuOpen.value = false
  void router.push(path)
}

function closeAccountMenuOnOutsideClick(event: PointerEvent) {
  if (!accountArea.value?.contains(event.target as Node)) accountMenuOpen.value = false
}

function closeAccountMenuOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') accountMenuOpen.value = false
}
</script>

<template>
  <aside class="cx-sidebar" :class="{ rail }">
    <div class="sidebar-brand" :class="{ rail }">
      <img v-if="!rail" class="brand-logo" src="/infinia-logo.svg" alt="" />
      <span v-if="!rail" class="sidebar-brand-name">{{ $t('brand') }}</span>
      <button
        v-if="!autoRail"
        class="cx-iconbtn cx-iconbtn--sm"
        :title="rail ? $t('sidebar.expand') : $t('sidebar.collapse')"
        :aria-label="rail ? $t('sidebar.expand') : $t('sidebar.collapse')"
        @click="setCollapsed(!rail)"
      ><i class="mdi" :class="rail ? 'mdi-dock-right' : 'mdi-dock-left'" /></button>
    </div>

    <div class="sidebar-new-chat-wrap">
      <button v-if="!rail" class="sidebar-new-chat" @click="startChat">
        <i class="mdi mdi-plus" />
        <span>{{ $t('sidebar.newChat') }}</span>
      </button>
      <button v-else class="cx-iconbtn sidebar-rail-action" :title="$t('sidebar.newChat')" @click="startChat">
        <i class="mdi mdi-plus" />
      </button>
    </div>

    <nav class="sidebar-primary-nav" :aria-label="$t('sidebar.primaryNavigation')">
      <button
        v-for="item in primaryNav"
        :key="item.key"
        class="cx-nav-item sidebar-nav-button"
        :class="{ rail, active: route.path === item.to }"
        :title="rail ? $t(item.labelKey) : undefined"
        @click="router.push(item.to)"
      >
        <i class="mdi" :class="item.icon" />
        <span v-if="!rail" class="cx-nav-label">{{ $t(item.labelKey) }}</span>
      </button>
    </nav>

    <div v-if="!rail" class="sidebar-history">
      <div v-if="ai.conversations.length" class="cx-subheader">{{ $t('sidebar.history') }}</div>
      <div
        v-for="conversation in ai.conversations"
        :key="conversation.id"
        class="cx-nav-item sidebar-nav-button sidebar-conversation"
        :class="{ active: route.name === 'ai' && conversation.id === ai.activeId }"
        role="button"
        tabindex="0"
        @click="openConversation(conversation.id)"
        @keydown.enter="openConversation(conversation.id)"
        @keydown.space.prevent="openConversation(conversation.id)"
      >
        <span class="cx-nav-label">{{ conversation.title || $t('sidebar.untitled') }}</span>
        <button
          class="cx-iconbtn cx-iconbtn--sm sidebar-remove-conversation"
          :aria-label="$t('aichat.clear')"
          @click.stop="ai.removeConversation(conversation.id)"
        ><i class="mdi mdi-close" /></button>
      </div>
    </div>
    <div v-else class="cx-grow" />

    <div ref="accountArea" class="sidebar-account" :class="{ rail }">
      <div v-if="accountMenuOpen" class="sidebar-account-menu" role="menu">
        <div class="sidebar-account-summary">
          <span class="sidebar-avatar sidebar-avatar--large">
            <img v-if="account.user?.avatarUrl" :src="account.user.avatarUrl" alt="" />
            <span v-else>{{ account.initials }}</span>
          </span>
          <span class="sidebar-account-copy">
            <strong>{{ account.displayName }}</strong>
            <span v-if="account.user?.email">{{ account.user.email }}</span>
            <span v-else>{{ $t('account.localAccount') }}</span>
          </span>
        </div>
        <button class="sidebar-account-menu-item" role="menuitem" @click="navigateFromAccountMenu('/account')">
          <i class="mdi mdi-account-outline" />
          <span>{{ $t('account.details') }}</span>
        </button>
        <button class="sidebar-account-menu-item" role="menuitem" @click="navigateFromAccountMenu('/settings')">
          <i class="mdi mdi-cog-outline" />
          <span>{{ $t('sidebar.settings') }}</span>
        </button>
      </div>

      <button
        class="sidebar-user-button"
        :class="{ rail }"
        :title="rail ? account.displayName : undefined"
        :aria-expanded="accountMenuOpen"
        aria-haspopup="menu"
        :aria-label="$t('account.menuFor', { name: account.displayName })"
        @click="toggleAccountMenu"
      >
        <span class="sidebar-avatar">
          <img v-if="account.user?.avatarUrl" :src="account.user.avatarUrl" alt="" />
          <span v-else>{{ account.initials }}</span>
        </span>
        <span v-if="!rail" class="cx-nav-label">{{ account.displayName }}</span>
      </button>

      <button
        class="cx-iconbtn cx-iconbtn--sm sidebar-about-button"
        :class="{ active: route.path === '/about' }"
        :title="$t('sidebar.about')"
        :aria-label="$t('sidebar.about')"
        @click="router.push('/about')"
      ><i class="mdi mdi-information-outline" /></button>
    </div>
  </aside>
</template>

<style scoped>
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 44px;
  padding: 4px 11px;
}
.sidebar-brand.rail { justify-content: center; padding-inline: 0; }
.brand-logo { width: 28px; height: 28px; flex: 0 0 auto; object-fit: contain; }
.sidebar-brand-name { flex: 1 1 auto; min-width: 0; overflow: hidden; font-weight: 600; white-space: nowrap; }
.sidebar-new-chat-wrap { padding: 3px 8px 5px; }
.sidebar-new-chat {
  width: 100%;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 0;
  border-radius: 9px;
  background: rgb(var(--v-theme-primary));
  color: rgb(var(--v-theme-on-primary));
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.sidebar-new-chat:hover { opacity: .88; }
.sidebar-new-chat .mdi { font-size: 18px; }
.sidebar-rail-action { margin: 0 auto; }
.sidebar-primary-nav { padding-top: 2px; }
.sidebar-nav-button { width: calc(100% - 12px); border: 0; background: transparent; text-align: left; font: inherit; }
.sidebar-history { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding-bottom: 7px; }
.sidebar-conversation { color: rgb(var(--v-theme-secondary)); }
.sidebar-remove-conversation { margin-right: -5px; }
.sidebar-account {
  position: relative;
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 7px 7px 0;
  border-top: 1px solid rgb(var(--v-theme-outline-variant));
}
.sidebar-account.rail { flex-direction: column; padding-inline: 6px; }
.sidebar-user-button {
  min-width: 0;
  flex: 1 1 auto;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 6px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: rgb(var(--v-theme-on-surface));
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.sidebar-user-button:hover { background: rgb(var(--v-theme-surface-container-high)); }
.sidebar-user-button.rail { width: 38px; flex: 0 0 38px; justify-content: center; padding: 0; }
.sidebar-avatar {
  width: 27px;
  height: 27px;
  flex: 0 0 27px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border-radius: 8px;
  background: rgb(var(--v-theme-surface-container-highest));
  font-size: 12px;
  font-weight: 650;
}
.sidebar-avatar--large { width: 32px; height: 32px; flex-basis: 32px; }
.sidebar-avatar img { width: 100%; height: 100%; object-fit: cover; }
.sidebar-about-button.active { background: rgb(var(--v-theme-surface-container-highest)); color: rgb(var(--v-theme-on-surface)); }
.sidebar-account-menu {
  position: absolute;
  z-index: 10;
  left: 7px;
  right: 7px;
  bottom: 48px;
  padding: 6px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 11px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 12px 28px rgba(0, 0, 0, .2);
}
.sidebar-account.rail .sidebar-account-menu { left: 48px; right: auto; bottom: 0; width: 220px; }
.sidebar-account-summary { display: flex; align-items: center; gap: 10px; padding: 7px 8px 10px; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); }
.sidebar-account-copy { min-width: 0; display: flex; flex-direction: column; }
.sidebar-account-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.sidebar-account-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: rgb(var(--v-theme-secondary)); font-size: 11px; }
.sidebar-account-menu-item {
  width: 100%;
  height: 34px;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 3px;
  padding: 0 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgb(var(--v-theme-on-surface));
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.sidebar-account-menu-item:hover { background: rgb(var(--v-theme-surface-container-high)); }
.sidebar-account-menu-item .mdi { font-size: 18px; color: rgb(var(--v-theme-secondary)); }
</style>
