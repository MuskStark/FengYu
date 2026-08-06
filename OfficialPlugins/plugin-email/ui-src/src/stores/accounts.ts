import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { invoke } from '../sdk'

export interface Account { id: number; displayName: string; email: string; defaultAccount?: boolean; smtpHost?: string; smtpPort?: number; smtpSecurity?: string; smtpSkipCertVerify?: boolean; imapHost?: string; imapPort?: number; imapSecurity?: string; imapSkipCertVerify?: boolean }
export interface AccountDraft extends Omit<Account, 'id'> { id?: number; password?: string }

export const useAccountsStore = defineStore('email-accounts', () => {
  const accounts = ref<Account[]>([])
  const selectedId = ref<number>()
  const draft = ref<AccountDraft>({ displayName: '', email: '', smtpHost: '', smtpPort: 587, smtpSecurity: 'STARTTLS', imapHost: '', imapPort: 993, imapSecurity: 'SSL' })
  const publicDraft = computed(() => { const { password: _password, ...safe } = draft.value; return safe })
  function select(id: number) { selectedId.value = id }
  function setDraft(value: AccountDraft) { draft.value = { ...value } }
  async function load() {
    const result = await invoke<{ accounts: Account[] }>('email_accounts_list')
    accounts.value = result.accounts ?? []
    if (!selectedId.value) selectedId.value = accounts.value.find(item => item.defaultAccount)?.id ?? accounts.value[0]?.id
  }
  async function save() { await invoke('email_account_save', { ...draft.value }); draft.value.password = ''; await load() }
  return { accounts, selectedId, draft, publicDraft, select, setDraft, load, save }
})
