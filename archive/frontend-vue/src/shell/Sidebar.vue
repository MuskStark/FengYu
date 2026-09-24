<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/settings'
import { useAiSessionStore } from '@/stores/aiSession'
import type { ConversationSortBy, ProjectGroup } from './sidebarProjects'
import { groupConversations, sortForView } from './sidebarProjects'
import { useAccountStore } from '@/stores/account'
import { useNotificationsStore } from '@/stores/notifications'
import { useUpdateStore } from '@/stores/update'
import { confirmAction } from '@/mf/desktop'
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
const { t, locale } = useI18n()

const accountMenuOpen = ref(false)
const accountArea = ref<HTMLElement | null>(null)
/** The notification panel opens from the account menu — parent-owned like the menu itself. */
const notificationOpen = ref(false)

const primaryNav = [
  { key: 'chat', to: '/', labelKey: 'sidebar.newChat', icon: 'mdi-message-outline' },
  // FengyuFlow's main surface is the flow canvas itself — a fresh builder graph
  // (Start node + coach note + template cards), not a flow library listing.
  {
    key: 'agent',
    to: '/flows/new',
    labelKey: 'sidebar.agent',
    icon: 'mdi-vector-polyline',
  },
  { key: 'schedules', to: '/schedules', labelKey: 'schedules.title', icon: 'mdi-calendar-clock-outline' },
  { key: 'tools', to: '/tools', labelKey: 'sidebar.all', icon: 'mdi-view-grid-outline' },
  { key: 'store', to: '/store', labelKey: 'sidebar.store', icon: 'mdi-storefront-outline' },
]

onMounted(() => {
  void ai.loadHistory()
  void account.load().catch(() => {
    // Keep the local shell usable if a future remote account provider is offline.
  })
  document.addEventListener('pointerdown', closeAccountMenuOnOutsideClick)
  document.addEventListener('pointerdown', closeFilterMenuOnOutsideClick)
  document.addEventListener('keydown', closeAccountMenuOnEscape)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeAccountMenuOnOutsideClick)
  document.removeEventListener('pointerdown', closeFilterMenuOnOutsideClick)
  document.removeEventListener('keydown', closeAccountMenuOnEscape)
})

function startChat() {
  // Reuses an existing empty conversation when one is around instead of stacking
  // blank untitled rows on every click.
  ai.newChat()
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
  // Switching during a live stream is safe: generation keeps writing into the
  // backgrounded conversation and this only changes the visible transcript.
  void ai.select(id)
  if (route.name !== 'ai') void router.push('/')
}

// ── ZCode-style project grouping ────────────────────────────────────────────────────
// A segmented switch above the history picks the organization: "chats" is the flat recent
// list (every conversation, workspace-bound or not); "projects" groups workspace-bound
// conversations under collapsible project headers. The store stays flat — display-only.

type HistoryView = 'chats' | 'projects'
const HISTORY_VIEW_KEY = 'fengyu-sidebar-history-view'
const viewMode = ref<HistoryView>(loadHistoryView())

function loadHistoryView(): HistoryView {
  try {
    const raw = localStorage.getItem(HISTORY_VIEW_KEY)
    return raw === 'chats' ? 'chats' : 'projects'
  } catch {
    return 'projects'
  }
}

function setHistoryView(view: HistoryView) {
  viewMode.value = view
  try {
    localStorage.setItem(HISTORY_VIEW_KEY, view)
  } catch {
    /* private mode — the pick just won't survive a restart */
  }
}

// Sliding-pill indicator (ZCode's organize-by Tabs): the active tab's measured box drives an
// absolutely-positioned pill behind the labels, so the switch animates instead of snapping.
// Re-measured on view/locale change and observed for size changes (late font load, resize).
const chatsTabRef = ref<HTMLButtonElement | null>(null)
const projectsTabRef = ref<HTMLButtonElement | null>(null)
const tabsShellRef = ref<HTMLElement | null>(null)
const indicator = ref({ width: 0, x: 0 })
let tabResizeObserver: ResizeObserver | null = null

const indicatorStyle = computed(() => ({
  width: `${indicator.value.width}px`,
  transform: `translateX(${indicator.value.x}px)`,
  opacity: indicator.value.width > 0 ? '1' : '0',
}))

function measureIndicator() {
  const el = viewMode.value === 'chats' ? chatsTabRef.value : projectsTabRef.value
  if (!el) return
  indicator.value = { width: el.offsetWidth, x: el.offsetLeft }
}

watch([viewMode, locale], () => void nextTick(measureIndicator))

onMounted(() => {
  void nextTick(measureIndicator)
  if (typeof ResizeObserver !== 'undefined' && tabsShellRef.value) {
    tabResizeObserver = new ResizeObserver(measureIndicator)
    tabResizeObserver.observe(tabsShellRef.value)
    if (chatsTabRef.value) tabResizeObserver.observe(chatsTabRef.value)
    if (projectsTabRef.value) tabResizeObserver.observe(projectsTabRef.value)
  }
})

onBeforeUnmount(() => {
  tabResizeObserver?.disconnect()
  tabResizeObserver = null
})

const grouping = computed(() => groupConversations(ai.conversations, taskSortBy.value))
/** Flat ("chats") view order follows the same sort key as the project grouping. */
const flatConversations = computed(() => sortForView(ai.conversations, taskSortBy.value))

// Sort preference behind the toolbar's view-options menu (ZCode: sortBy updated/created).
const TASK_SORT_KEY = 'fengyu-sidebar-task-sort'
const taskSortBy = ref<ConversationSortBy>(loadTaskSort())

function loadTaskSort(): ConversationSortBy {
  try {
    return localStorage.getItem(TASK_SORT_KEY) === 'created' ? 'created' : 'updated'
  } catch {
    return 'updated'
  }
}

function setTaskSortBy(value: ConversationSortBy) {
  taskSortBy.value = value
  filterMenuOpen.value = false
  try {
    localStorage.setItem(TASK_SORT_KEY, value)
  } catch {
    /* private mode — the pick just won't survive a restart */
  }
}

const sortOptions = computed(() => [
  { id: 'updated' as const, icon: 'mdi-message-check-outline', label: t('sidebar.sortByUpdated') },
  { id: 'created' as const, icon: 'mdi-message-plus-outline', label: t('sidebar.sortByCreated') },
])

// The view-options dropdown closes on any pointerdown outside its trigger+menu pair.
const filterMenuOpen = ref(false)

function closeFilterMenuOnOutsideClick(event: PointerEvent) {
  const within = (event.target as HTMLElement).closest('[data-menu]')?.getAttribute('data-menu')
  if (within !== 'filter') filterMenuOpen.value = false
}

/** New chat bound to one project: mints the conversation, then attaches the same workspace root. */
async function newProjectConversation(root: string) {
  const conv = ai.newConversation()
  try {
    await ai.setWorkspace(conv, root)
  } catch {
    ai.error = t('aichat.workspaceSetFailed')
  }
  if (route.name !== 'ai') void router.push('/')
}

const COLLAPSED_PROJECTS_KEY = 'fengyu-sidebar-collapsed-projects'
const collapsedProjects = ref<Set<string>>(loadCollapsedProjects())

function loadCollapsedProjects(): Set<string> {
  try {
    const raw = localStorage.getItem(COLLAPSED_PROJECTS_KEY)
    return new Set(raw ? (JSON.parse(raw) as string[]) : [])
  } catch {
    return new Set()
  }
}

function persistCollapsedProjects() {
  try {
    localStorage.setItem(COLLAPSED_PROJECTS_KEY, JSON.stringify([...collapsedProjects.value]))
  } catch {
    /* storage unavailable (private mode) — collapse state just won't survive a restart */
  }
}

function toggleProject(root: string) {
  const next = new Set(collapsedProjects.value)
  if (next.has(root)) next.delete(root)
  else next.add(root)
  collapsedProjects.value = next
  persistCollapsedProjects()
}

/** A collapsed group still expands when it owns the active conversation — the highlight must stay visible. */
function projectExpanded(group: ProjectGroup): boolean {
  if (!collapsedProjects.value.has(group.root)) return true
  return group.conversations.some(conversation => conversation.id === ai.activeId)
}

/** One-click fold state for every project header: mixed states fold, fully-folded unfolds. */
const allProjectsCollapsed = computed(() =>
  grouping.value.projects.length > 0
  && grouping.value.projects.every(group => collapsedProjects.value.has(group.root)))

function toggleAllProjects() {
  const fold = !allProjectsCollapsed.value
  collapsedProjects.value = fold
    ? new Set(grouping.value.projects.map(group => group.root))
    : new Set()
  persistCollapsedProjects()
}

/** Deleting a conversation is irreversible — confirm first, like every other destructive action. */
async function removeConversation(id: number) {
  if (!await confirmAction(t('aichat.deleteConversationConfirm'))) return
  void ai.removeConversation(id)
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
      <!-- View switch (ZCode's organize-by Tabs): sliding pill behind the labels; fold-all ghost
           button beside it, view-options dropdown on the trailing side. -->
      <div v-if="ai.conversations.length" class="sidebar-history-toolbar">
        <div class="sidebar-toolbar-group">
          <div ref="tabsShellRef" class="sidebar-view-tabs" role="tablist" :aria-label="$t('sidebar.historyView')">
            <span class="sidebar-view-tabs__indicator" :style="indicatorStyle" aria-hidden="true" />
            <button
              ref="chatsTabRef"
              role="tab"
              class="sidebar-view-tab"
              :class="{ 'sidebar-view-tab--active': viewMode === 'chats' }"
              :aria-selected="viewMode === 'chats'"
              @click="setHistoryView('chats')"
            >
              <i class="mdi mdi-message-text-outline" aria-hidden="true" />{{ $t('sidebar.viewChats') }}
            </button>
            <button
              ref="projectsTabRef"
              role="tab"
              class="sidebar-view-tab"
              :class="{ 'sidebar-view-tab--active': viewMode === 'projects' }"
              :aria-selected="viewMode === 'projects'"
              @click="setHistoryView('projects')"
            >
              <i class="mdi mdi-folder-outline" aria-hidden="true" />{{ $t('sidebar.viewProjects') }}
            </button>
          </div>
          <button
            v-if="viewMode === 'projects' && grouping.projects.length"
            class="cx-iconbtn cx-iconbtn--sm sidebar-fold-all"
            :title="allProjectsCollapsed ? $t('sidebar.expandAllProjects') : $t('sidebar.collapseAllProjects')"
            :aria-label="allProjectsCollapsed ? $t('sidebar.expandAllProjects') : $t('sidebar.collapseAllProjects')"
            @click="toggleAllProjects"
          ><i class="mdi" :class="allProjectsCollapsed ? 'mdi-arrow-expand-vertical' : 'mdi-arrow-collapse-vertical'" /></button>
        </div>
        <div class="sidebar-toolbar-group">
          <div class="sidebar-filter" data-menu="filter">
            <button
              class="cx-iconbtn cx-iconbtn--sm"
              :class="{ 'sidebar-filter--open': filterMenuOpen }"
              :title="$t('sidebar.taskViewOptions')"
              :aria-label="$t('sidebar.taskViewOptions')"
              :aria-expanded="filterMenuOpen"
              @click="filterMenuOpen = !filterMenuOpen"
            ><i class="mdi mdi-filter-variant" /></button>
            <div v-if="filterMenuOpen" class="sidebar-filter-menu" role="menu">
              <div class="cx-muted sidebar-filter-menu__label">{{ $t('sidebar.sortBy') }}</div>
              <button
                v-for="option in sortOptions"
                :key="option.id"
                class="cx-btn cx-btn--text sidebar-filter-menu__item"
                role="menuitemradio"
                :aria-checked="taskSortBy === option.id"
                @click="setTaskSortBy(option.id)"
              >
                <i class="mdi" :class="option.icon" />
                <span class="cx-grow">{{ option.label }}</span>
                <i v-if="taskSortBy === option.id" class="mdi mdi-check" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <template v-if="viewMode === 'projects'">
      <!-- Workspace projects: one collapsible header per coding workspace, conversations under it. -->
      <template v-for="group in grouping.projects" :key="group.root">
        <!-- A div-button (like the conversation rows) so the inline new-chat affordance can be
             a real <button> — nested buttons are invalid HTML and Vue warns at dev time. -->
        <div
          class="sidebar-project"
          :class="{ 'sidebar-project--open': projectExpanded(group) }"
          :title="group.root"
          role="button"
          tabindex="0"
          :aria-expanded="projectExpanded(group)"
          @click="toggleProject(group.root)"
          @keydown.enter="toggleProject(group.root)"
          @keydown.space.prevent="toggleProject(group.root)"
        >
          <i class="mdi sidebar-project__chevron" :class="projectExpanded(group) ? 'mdi-chevron-down' : 'mdi-chevron-right'" />
          <i class="mdi mdi-folder-outline" />
          <span class="cx-nav-label">{{ group.name }}</span>
          <button
            class="cx-iconbtn sidebar-project__add"
            :title="$t('sidebar.newProjectConversation')"
            :aria-label="$t('sidebar.newProjectConversation')"
            @click.stop="newProjectConversation(group.root)"
          ><i class="mdi mdi-plus" /></button>
        </div>
        <div
          v-for="conversation in group.conversations"
          v-show="projectExpanded(group)"
          :key="conversation.id"
          class="cx-nav-item sidebar-nav-button sidebar-conversation sidebar-conversation--project"
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
            :aria-label="$t('aichat.deleteConversation')"
            :title="$t('aichat.deleteConversation')"
            @click.stop="removeConversation(conversation.id)"
          ><i class="mdi mdi-close" /></button>
        </div>
      </template>

      <!-- Projects view is projects-only: plain chats stay in the "chats" view. -->
      <div v-if="!grouping.projects.length" class="sidebar-projects-empty">
        <i class="mdi mdi-folder-plus-outline" />
        <span>{{ $t('sidebar.noProjects') }}</span>
      </div>
      </template>

      <!-- Flat view: every conversation, workspace or not, ordered by the toolbar's sort. -->
      <template v-else>
        <div class="cx-subheader">{{ $t('sidebar.history') }}</div>
        <div
          v-for="conversation in flatConversations"
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
            :aria-label="$t('aichat.deleteConversation')"
            :title="$t('aichat.deleteConversation')"
            @click.stop="removeConversation(conversation.id)"
          ><i class="mdi mdi-close" /></button>
        </div>
      </template>
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
.sidebar-history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  padding: 2px 12px 8px 10px;
}
.sidebar-toolbar-group {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
/* ZCode's organize-by tabs: a 28px pill shell on an elevated surface with a deeper pill
   sliding behind the active label (measured in script — see measureIndicator). */
.sidebar-view-tabs {
  position: relative;
  display: inline-flex;
  height: 28px;
  padding: 2px;
  border-radius: 999px;
  background: rgb(var(--v-theme-surface-container-high));
  overflow: hidden;
}
.sidebar-view-tabs__indicator {
  position: absolute;
  top: 2px;
  bottom: 2px;
  left: 0;
  border-radius: 999px;
  background: rgb(var(--v-theme-surface));
  transition: transform 0.2s ease-out, width 0.2s ease-out, opacity 0.2s ease-out;
}
.sidebar-view-tab {
  position: relative;
  z-index: 1;
  height: 24px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 8px 0 6px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: rgb(var(--v-theme-secondary));
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  user-select: none;
  transition: color 0.2s ease-out;
}
.sidebar-view-tab:hover { color: rgb(var(--v-theme-on-surface)); }
.sidebar-view-tab--active { color: rgb(var(--v-theme-on-surface)); }
.sidebar-view-tab:focus-visible {
  outline: 2px solid rgba(var(--v-theme-on-surface), 0.72);
  outline-offset: -2px;
}
.sidebar-view-tab .mdi { font-size: 12px; flex: 0 0 auto; }
.sidebar-fold-all .mdi { font-size: 14px; }

/* View-options dropdown (ZCode's ListFilter menu): compact radio rows over a menu surface. */
.sidebar-filter { position: relative; }
.cx-iconbtn.sidebar-filter--open {
  background: var(--cx-hover-strong);
  color: rgb(var(--v-theme-on-surface));
}
.sidebar-filter-menu {
  position: absolute;
  top: calc(100% + 5px);
  right: 0;
  z-index: 20;
  min-width: 190px;
  padding: 6px;
  border: 1px solid var(--cx-border);
  border-radius: var(--cx-radius);
  background: rgb(var(--v-theme-surface-container-high));
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.18);
}
.sidebar-filter-menu__label {
  padding: 5px 10px 6px;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.cx-btn.sidebar-filter-menu__item {
  width: 100%;
  height: 30px;
  justify-content: flex-start;
  font-size: 12.5px;
}
.sidebar-filter-menu__item .mdi:first-child { font-size: 16px; }
.sidebar-filter-menu__item .mdi-check { font-size: 16px; }

/* New-chat-in-project affordance on the group header (hover-revealed like the row deletes). */
.cx-iconbtn.sidebar-project__add {
  width: 24px;
  height: 24px;
  margin-right: -3px;
  opacity: 0;
}
.cx-iconbtn.sidebar-project__add .mdi { font-size: 15px; }
.sidebar-project:hover .sidebar-project__add,
.sidebar-project:focus-visible .sidebar-project__add { opacity: 1; }
.sidebar-conversation { color: rgb(var(--v-theme-secondary)); }
.cx-nav-item.sidebar-conversation--project { padding-left: 28px; }
.sidebar-remove-conversation { margin-right: -5px; }
.sidebar-project {
  width: calc(100% - 12px);
  height: 28px;
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 4px 6px 1px;
  padding: 0 7px;
  border: 0;
  border-radius: var(--cx-radius-sm);
  background: transparent;
  color: rgb(var(--v-theme-secondary));
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  user-select: none;
}
.sidebar-project:first-of-type { margin-top: 1px; }
.sidebar-project:hover { background: var(--cx-hover); color: rgb(var(--v-theme-on-surface)); }
.sidebar-project:focus-visible {
  outline: 2px solid rgba(var(--v-theme-on-surface), 0.72);
  outline-offset: -2px;
}
.sidebar-project .mdi { flex: 0 0 auto; }
.sidebar-project__chevron { font-size: 13px; opacity: 0.55; }
.sidebar-project .mdi-folder-outline { font-size: 16px; opacity: 0.75; }
.sidebar-projects-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 26px 18px;
  color: rgb(var(--v-theme-secondary));
  font-size: 12px;
  line-height: 1.5;
  text-align: center;
}
.sidebar-projects-empty .mdi { font-size: 22px; opacity: 0.5; }
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
