<script setup lang="ts">
import { computed, onMounted, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { FyPluginPage, FyPluginShell, useFengYuClient, useFengYuNotify } from '@infinia/plugin-ui'
import ComposeTab from './components/ComposeTab.vue'
import BatchTab from './components/BatchTab.vue'
import AddressBookTab from './components/AddressBookTab.vue'
import CollectTab from './components/CollectTab.vue'
import SendRecordsView from './components/SendRecordsView.vue'
import AccountSettingsView from './components/AccountSettingsView.vue'
import { useAccountsStore } from './stores/accounts'
import { useNavigationStore, type WorkspaceId } from './stores/navigation'
import { actionable } from './sdk'

const { t } = useI18n()
const client = useFengYuClient()
const { notify } = useFengYuNotify(client)
const accounts = useAccountsStore()
const navigation = useNavigationStore()
const workspaces: Record<WorkspaceId, Component> = {
  compose: ComposeTab,
  batch: BatchTab,
  contacts: AddressBookTab,
  archive: CollectTab,
  records: SendRecordsView,
  accounts: AccountSettingsView,
}
const activeWorkspace = computed(() => workspaces[navigation.active])
const navItems = computed(() => navigation.items.map(item => ({
  value: item.id,
  title: t(item.labelKey),
  icon: item.icon,
})))
const activeNavigation = computed<string>({
  get: () => navigation.active,
  set: value => { navigation.active = value as WorkspaceId },
})

onMounted(() => accounts.load().catch(value => {
  void notify(actionable(value, t('accounts.loading')), { tone: 'error' })
}))
</script>

<template>
  <FyPluginShell v-model="activeNavigation" :title="t('app.title')" :items="navItems">
      <FyPluginPage :max-width="1200">
          <component :is="activeWorkspace" :key="navigation.active" />
      </FyPluginPage>
  </FyPluginShell>
</template>
