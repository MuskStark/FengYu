<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useAiSessionStore } from '@/stores/aiSession'
import { useAccountStore } from '@/stores/account'
import { useNotificationsStore } from '@/stores/notifications'
import { useUpdateStore } from '@/stores/update'
import NotificationCenter from './NotificationCenter.vue'
import { SIDEBAR_DEFAULT_WIDTH } from './sidebar-layout'

const props = withDefaults(defineProps<{
  macTitleBar?: boolean
  /** ZCode-style collapse: the shell owns the state, the sidebar only renders it. */
  collapsed?: boolean
  /** Expanded width in px — driven by the shell's drag handle. */
  width?: number
  /** True while the user drags the resize handle: width follows the pointer without transition. */
  resizing?: boolean
}>(), {
  macTitleBar: false,
  collapsed: false,
  width: SIDEBAR_DEFAULT_WIDTH,
  resizing: false,
})

const settings = useSettingsStore()
const ai = useAiSessionStore()
const account = useAccountStore()
const notifications = useNotificationsStore()
const update = useUpdateStore()
const router = useRouter()
const route = useRoute()

const accountMenuOpen = ref(false)
const accountArea = ref<HTMLElement | null>(null)
/** The notification panel opens from the account menu — parent-owned like the menu itself. */
const notificationOpen = ref(false)

const primaryNav = [
  { key: 'chat', to: '/', labelKey: 'sidebar.newChat', icon: 'mdi-message-outline' },
  { key: 'tools', to: '/tools', labelKey: 'sidebar.all', icon: 'mdi-view-grid-outline' },
  { key: 'plugins', to: '/plugins', labelKey: 'sidebar.plugins', icon: 'mdi-shopping-outline' },
  { key: 'store', to: '/store', labelKey: 'sidebar.store', icon: 'mdi-storefront-outline' },
  // FengyuFlow's main surface is the flow canvas itself — a fresh builder graph
  // (Start node + coach note + template cards), not a flow library listing.
  {
    key: 'agent',
    to: '/flows/new',
    labelKey: 'sidebar.agent',
    icon: 'mdi-vector-polyline',
  },
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

function openPrimary(item: typeof primaryNav[number]) {
  if (item.key === 'chat') {
    startChat()
    return
  }
  // The canvas entry keeps the current graph when one is already open — pushing
  // /flows/new again would only desync the URL from the on-canvas state.
  if (item.key === 'agent' && route.path.startsWith('/flows/')) return
  void router.push(item.to)
}

function openConversation(id: number) {
  void ai.select(id)
  if (route.name !== 'ai') void router.push('/')
}

function setCollapsed(collapsed: boolean) {
  accountMenuOpen.value = false
  // The store reverts the collapse state when the save fails; the sidebar has no
  // error surface of its own, so the rejection is deliberately swallowed here.
  settings.setSidebarCollapsed(collapsed).catch(() => {})
}

function toggleAccountMenu() {
  accountMenuOpen.value = !accountMenuOpen.value
}

function navigateFromAccountMenu(path: string) {
  accountMenuOpen.value = false
  void router.push(path)
}

/** Account-menu "Notifications" entry: swap the menu for the notification panel in place. */
function openNotifications() {
  accountMenuOpen.value = false
  notificationOpen.value = true
}

function closeAccountMenuOnOutsideClick(event: PointerEvent) {
  if (!accountArea.value?.contains(event.target as Node)) accountMenuOpen.value = false
}

function closeAccountMenuOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') accountMenuOpen.value = false
}
</script>

<template>
  <aside
    class="cx-sidebar"
    :class="{ collapsed, resizing }"
    :style="{ '--cx-sidebar-width': `${width}px` }"
  >
    <!-- Fixed-width inner shell: the aside itself animates to width 0, but its contents keep
         their expanded width so text never reflows mid-collapse. -->
    <div class="sidebar-inner">
    <div v-if="macTitleBar" class="sidebar-window-bar" aria-hidden="true">
      <span class="sidebar-window-drag sidebar-window-drag--left" />
      <span class="sidebar-window-drag sidebar-window-drag--right" />
    </div>

    <div class="sidebar-brand" :class="{ collapsed, 'mac-titlebar-brand': macTitleBar }">
      <img v-if="!collapsed || macTitleBar" class="brand-logo" src="/infinia-logo.svg" alt="" />
      <span v-if="!collapsed" class="sidebar-brand-name">{{ $t('brand') }}</span>
      <button
        v-if="!macTitleBar"
        class="cx-iconbtn cx-iconbtn--sm"
        :title="collapsed ? $t('sidebar.expand') : $t('sidebar.collapse')"
        :aria-label="collapsed ? $t('sidebar.expand') : $t('sidebar.collapse')"
        @click="setCollapsed(!collapsed)"
      ><i class="mdi" :class="collapsed ? 'mdi-dock-right' : 'mdi-dock-left'" /></button>
    </div>

    <nav class="sidebar-primary-nav" :aria-label="$t('sidebar.primaryNavigation')">
      <button
        v-for="item in primaryNav"
        :key="item.key"
        class="cx-nav-item sidebar-nav-button"
        :class="{ collapsed, active: route.path === item.to || (item.key === 'agent' && route.path.startsWith('/flows/')) }"
        :title="collapsed ? $t(item.labelKey) : undefined"
        @click="openPrimary(item)"
      >
        <i class="mdi" :class="item.icon" />
        <span v-if="!collapsed" class="cx-nav-label">{{ $t(item.labelKey) }}</span>
      </button>
    </nav>

    <div v-if="!collapsed" class="sidebar-history">
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

    <div ref="accountArea" class="sidebar-account" :class="{ collapsed }">
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
        <button class="sidebar-account-menu-item" role="menuitem" @click="openNotifications">
          <i class="mdi" :class="notifications.unreadCount > 0 ? 'mdi-bell-badge' : 'mdi-bell-outline'" />
          <span>{{ $t('notifications.title') }}</span>
          <span v-if="notifications.unreadCount > 0" class="sidebar-account-menu-count">
            {{ notifications.unreadCount }}
          </span>
        </button>
      </div>

      <button
        class="sidebar-user-button"
        :class="{ collapsed }"
        :title="collapsed ? account.displayName : undefined"
        :aria-expanded="accountMenuOpen"
        aria-haspopup="menu"
        :aria-label="$t('account.menuFor', { name: account.displayName })"
        @click="toggleAccountMenu"
      >
        <span class="sidebar-avatar">
          <img v-if="account.user?.avatarUrl" :src="account.user.avatarUrl" alt="" />
          <span v-else>{{ account.initials }}</span>
          <!-- Unread beacon: the bell lives in the account menu now, so the avatar dot is
               what makes new notifications discoverable without opening it. -->
          <span v-if="notifications.unreadCount > 0" class="sidebar-user-badge" aria-hidden="true" />
        </span>
        <span v-if="!collapsed" class="cx-nav-label">{{ account.displayName }}</span>
      </button>

      <NotificationCenter :open="notificationOpen" :rail="collapsed" @close="notificationOpen = false" />

      <button
        class="cx-iconbtn cx-iconbtn--sm sidebar-about-button"
        :class="{ active: route.path === '/about' }"
        :title="update.updateAvailable ? $t('update.available', { version: update.latestVersion }) : $t('sidebar.about')"
        :aria-label="update.updateAvailable ? $t('update.available', { version: update.latestVersion }) : $t('sidebar.about')"
        @click="router.push('/about')"
      ><i class="mdi mdi-information-outline" />
       <!-- Update beacon: red dot until the newer release is installed (the update lives on About). -->
       <span v-if="update.updateAvailable" class="sidebar-about-badge" aria-hidden="true" /></button>
    </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar-inner {
  display: flex;
  flex-direction: column;
  width: var(--cx-sidebar-width, 256px);
  min-width: var(--cx-sidebar-width, 256px);
  height: 100%;
}
.sidebar-window-bar {
  position: relative;
  flex: 0 0 var(--cx-window-bar-height);
  min-height: var(--cx-window-bar-height);
  app-region: no-drag;
  -webkit-app-region: no-drag;
  user-select: none;
}
.sidebar-window-drag {
  position: absolute;
  inset-block: 0;
  app-region: drag;
  -webkit-app-region: drag;
}
.sidebar-window-drag--left { left: 0; width: 76px; }
.sidebar-window-drag--right { left: 120px; right: 0; }
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 44px;
  padding: 4px 11px;
}
.sidebar-brand.mac-titlebar-brand {
  flex: 0 0 40px;
  min-height: 40px;
  gap: 8px;
  padding: 2px 12px;
}
.sidebar-brand.mac-titlebar-brand .brand-logo { width: 26px; height: 26px; }
.sidebar-brand.mac-titlebar-brand .sidebar-brand-name { font-size: 15px; font-weight: 650; }
.sidebar-brand.collapsed { justify-content: center; padding-inline: 0; }
.brand-logo { width: 28px; height: 28px; flex: 0 0 auto; object-fit: contain; }
.sidebar-brand-name { flex: 1 1 auto; min-width: 0; overflow: hidden; font-weight: 600; white-space: nowrap; }
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
.sidebar-account.collapsed { flex-direction: column; padding-inline: 6px; }
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
.sidebar-user-button.collapsed { width: 38px; flex: 0 0 38px; justify-content: center; padding: 0; }
.sidebar-avatar {
  position: relative;
  width: 27px;
  height: 27px;
  flex: 0 0 27px;
  display: grid;
  place-items: center;
  overflow: visible;
  border-radius: 8px;
  background: rgb(var(--v-theme-surface-container-highest));
  font-size: 12px;
  font-weight: 650;
}
.sidebar-avatar--large { width: 32px; height: 32px; flex-basis: 32px; }
.sidebar-avatar img { width: 100%; height: 100%; object-fit: cover; border-radius: 8px; }
/* Unread beacon: the bell lives in the account menu now, so the avatar dot is what makes
 * new notifications discoverable without opening it. */
.sidebar-user-badge {
  position: absolute;
  top: -3px;
  right: -3px;
  width: 9px;
  height: 9px;
  border: 2px solid rgb(var(--v-theme-surface));
  border-radius: 50%;
  background: rgb(var(--v-theme-primary));
}
.sidebar-account-menu-count {
  margin-left: auto;
  min-width: 20px;
  padding: 1px 6px;
  border-radius: 999px;
  background: rgb(var(--v-theme-primary));
  color: rgb(var(--v-theme-on-primary));
  font-size: 11px;
  font-weight: 650;
  text-align: center;
}
.sidebar-about-button { position: relative; }
.sidebar-about-button.active { background: rgb(var(--v-theme-surface-container-highest)); color: rgb(var(--v-theme-on-surface)); }
/* Red update beacon on the About entry — mirrors the avatar's unread dot pattern. */
.sidebar-about-badge {
  position: absolute;
  top: 1px;
  right: 1px;
  width: 9px;
  height: 9px;
  border: 2px solid rgb(var(--v-theme-surface));
  border-radius: 50%;
  background: rgb(var(--v-theme-error));
}
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
.sidebar-account.collapsed .sidebar-account-menu { left: 48px; right: auto; bottom: 0; width: 220px; }
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
