<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import ComposeTab from './components/ComposeTab.vue'
import BatchTab from './components/BatchTab.vue'
import AddressBookTab from './components/AddressBookTab.vue'
import CollectTab from './components/CollectTab.vue'
import RecordsAccountsTab from './components/RecordsAccountsTab.vue'
import TaskRail from './components/TaskRail.vue'
import { useAccountsStore } from './stores/accounts'
import { useNavigationStore, type WorkspaceId } from './stores/navigation'
import { actionable } from './sdk'

const error = ref('')
const { t } = useI18n()
const accounts = useAccountsStore()
const navigation = useNavigationStore()
const workspaces: Record<WorkspaceId, Component> = {
  compose: ComposeTab,
  batch: BatchTab,
  contacts: AddressBookTab,
  archive: CollectTab,
  records: RecordsAccountsTab,
  accounts: RecordsAccountsTab,
}
const activeWorkspace = computed(() => workspaces[navigation.active])
const activeWorkspaceProps = computed(() => navigation.active === 'accounts'
  ? { initialView: 'accounts' }
  : navigation.active === 'records' ? { initialView: 'records' } : {})

onMounted(() => accounts.load().catch(value => { error.value = actionable(value, t('accounts.loading')) }))
</script>

<template>
  <v-app>
    <v-main>
      <div class="email-layout">
        <TaskRail />
        <main class="email-workspace">
          <v-alert v-if="error" type="error" closable @click:close="error = ''">{{ error }}</v-alert>
          <component :is="activeWorkspace" :key="navigation.active" v-bind="activeWorkspaceProps" />
        </main>
      </div>
    </v-main>
  </v-app>
</template>
