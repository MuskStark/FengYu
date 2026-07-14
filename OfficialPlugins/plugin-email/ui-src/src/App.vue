<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ComposeTab from './components/ComposeTab.vue'
import BatchTab from './components/BatchTab.vue'
import AddressBookTab from './components/AddressBookTab.vue'
import CollectTab from './components/CollectTab.vue'
import RecordsAccountsTab from './components/RecordsAccountsTab.vue'
import { useAccountsStore } from './stores/accounts'
import { actionable } from './sdk'

const tab = ref('compose'), error = ref('')
const accounts = useAccountsStore()
onMounted(() => accounts.load().catch(value => error.value = actionable(value, 'Loading accounts')))
</script>

<template>
  <v-app><v-main><div class="email-shell">
    <header><div><h1>Email Center</h1><p>Compose, automate, collect, and audit mail in one secure workspace.</p></div><v-chip color="primary" variant="tonal">SDK sandbox</v-chip></header>
    <v-alert v-if="error" type="error" closable @click:close="error=''">{{ error }}</v-alert>
    <v-tabs v-model="tab" color="primary" show-arrows>
      <v-tab value="compose">Compose</v-tab><v-tab value="batch">Batch Send</v-tab><v-tab value="contacts">Address Book</v-tab><v-tab value="collect">Collect Mail</v-tab><v-tab value="records">Records & Accounts</v-tab>
    </v-tabs>
    <v-window v-model="tab" class="tab-window">
      <v-window-item value="compose"><ComposeTab /></v-window-item>
      <v-window-item value="batch"><BatchTab /></v-window-item>
      <v-window-item value="contacts"><AddressBookTab /></v-window-item>
      <v-window-item value="collect"><CollectTab /></v-window-item>
      <v-window-item value="records"><RecordsAccountsTab /></v-window-item>
    </v-window>
  </div></v-main></v-app>
</template>
