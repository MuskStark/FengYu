<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAccountsStore } from '../stores/accounts'
import { actionable, checked, rpc } from '../sdk'
import ActionDialog from './ActionDialog.vue'

const { t } = useI18n(), accounts = useAccountsStore(), error = ref(''), notice = ref(''), busy = ref(false), deleteDialog = ref(false)
async function guard(action: string, task: () => Promise<void>): Promise<void> {
  busy.value = true; error.value = ''; notice.value = ''
  try { await task() } catch (value) { error.value = actionable(value, action) }
  finally { busy.value = false }
}
function newAccount(): void {
  accounts.setDraft({ displayName: '', email: '', password: '', smtpHost: '', smtpPort: 587,
    smtpSecurity: 'STARTTLS', smtpSkipCertVerify: false, imapHost: '', imapPort: 993,
    imapSecurity: 'SSL', imapSkipCertVerify: false, defaultAccount: false })
}
const testAccount = () => guard(t('accounts.testAction'), async () => {
  await checked(rpc.email_account_test({ accountId: accounts.draft.id! })); notice.value = t('accounts.testSuccess')
})
const testImapAccount = () => guard(t('accounts.testAction'), async () => {
  await checked(rpc.email_account_test_imap({ accountId: accounts.draft.id! })); notice.value = t('accounts.testSuccess')
})
const saveAccount = () => guard(t('accounts.saveAction'), async () => {
  await checked(rpc.email_account_save({ ...accounts.draft })); accounts.draft.password = ''; await accounts.load(); notice.value = t('accounts.saved')
})
const makeDefault = () => guard(t('accounts.defaultAction'), async () => {
  await checked(rpc.email_account_set_default({ id: accounts.draft.id! })); await accounts.load()
})
const removeAccount = () => {
  if (!accounts.draft.id) return
  deleteDialog.value = true
}
const confirmRemoveAccount = () => {
  if (!accounts.draft.id) return
  void guard(t('accounts.deleteAction'), async () => {
    await checked(rpc.email_account_delete({ id: accounts.draft.id! })); newAccount(); await accounts.load()
  })
}
</script>

<template>
  <v-card class="surface" variant="flat">
    <v-card-title>{{ t('accounts.title') }}</v-card-title>
    <v-card-text>
      <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert><v-alert v-if="notice" type="success" class="mb-4">{{ notice }}</v-alert>
      <div class="account-layout">
        <v-list><v-list-item v-for="account in accounts.accounts" :key="account.id" :title="account.displayName" :subtitle="account.email" @click="accounts.setDraft(account)" /><v-list-item :title="t('accounts.newAccount')" @click="newAccount" /></v-list>
        <div>
          <v-text-field v-model="accounts.draft.displayName" :label="t('accounts.displayName')" />
          <v-text-field v-model="accounts.draft.email" :label="t('contacts.email')" />
          <v-text-field v-model="accounts.draft.password" type="password" autocomplete="new-password" :label="t('accounts.password')" :hint="t('accounts.passwordHelp')" persistent-hint />
          <v-card variant="outlined" class="pa-4">
            <div class="text-subtitle-1 font-weight-bold mb-3">{{ t('accounts.smtpSection') }}</div>
            <div class="form-grid">
              <v-text-field class="full-row" v-model="accounts.draft.smtpHost" :label="t('accounts.smtp')" />
              <v-text-field v-model.number="accounts.draft.smtpPort" type="number" :label="t('accounts.port')" />
              <v-select v-model="accounts.draft.smtpSecurity" :items="['SSL','STARTTLS','PLAIN']" :label="t('accounts.security')" />
            </div>
            <div class="d-flex ga-2 mt-3"><v-btn data-testid="smtp-test" variant="tonal" :loading="busy" @click="testAccount">{{ t('accounts.testSmtp') }}</v-btn></div>
            <v-switch v-model="accounts.draft.smtpSkipCertVerify" color="warning" hide-details density="compact" class="mt-2" :label="t('accounts.skipCert')" />
            <v-alert v-if="accounts.draft.smtpSkipCertVerify" type="warning" variant="tonal" density="compact" class="mt-2">{{ t('accounts.skipCertWarn') }}</v-alert>
          </v-card>
          <v-card variant="outlined" class="pa-4">
            <div class="text-subtitle-1 font-weight-bold mb-3">{{ t('accounts.imapSection') }}</div>
            <div class="form-grid">
              <v-text-field class="full-row" v-model="accounts.draft.imapHost" :label="t('accounts.imap')" />
              <v-text-field v-model.number="accounts.draft.imapPort" type="number" :label="t('accounts.port')" />
              <v-select v-model="accounts.draft.imapSecurity" :items="['SSL','STARTTLS','PLAIN']" :label="t('accounts.security')" />
            </div>
            <div class="d-flex ga-2 mt-3"><v-btn data-testid="imap-test" variant="tonal" :loading="busy" @click="testImapAccount">{{ t('accounts.testImap') }}</v-btn></div>
            <v-switch v-model="accounts.draft.imapSkipCertVerify" color="warning" hide-details density="compact" class="mt-2" :label="t('accounts.skipCert')" />
            <v-alert v-if="accounts.draft.imapSkipCertVerify" type="warning" variant="tonal" density="compact" class="mt-2">{{ t('accounts.skipCertWarn') }}</v-alert>
          </v-card>
          <v-checkbox v-model="accounts.draft.defaultAccount" :label="t('accounts.defaultAccount')" />
          <div class="d-flex ga-2 justify-end"><v-btn v-if="accounts.draft.id" color="error" variant="text" @click="removeAccount">{{ t('common.delete') }}</v-btn><v-btn v-if="accounts.draft.id && !accounts.draft.defaultAccount" variant="tonal" @click="makeDefault">{{ t('accounts.makeDefault') }}</v-btn><v-btn data-testid="account-save" color="primary" :loading="busy" @click="saveAccount">{{ t('common.save') }}</v-btn></div>
        </div>
      </div>
    </v-card-text>
  </v-card>
  <ActionDialog v-model="deleteDialog" :title="t('accounts.deleteAction')" :message="t('accounts.deleteConfirm')"
    :confirm-text="t('common.delete')" destructive @confirm="confirmRemoveAccount" />
</template>
