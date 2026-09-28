import { create } from 'zustand'
import { checked, rpc } from '../sdk'

export interface Account { id: number; displayName: string; email: string; defaultAccount?: boolean; smtpHost?: string; smtpPort?: number; smtpSecurity?: string; smtpSkipCertVerify?: boolean; imapHost?: string; imapPort?: number; imapSecurity?: string; imapSkipCertVerify?: boolean }
export interface AccountDraft extends Omit<Account, 'id'> { id?: number; password?: string }

export const initialAccountDraft: AccountDraft = { displayName: '', email: '', smtpHost: '', smtpPort: 587, smtpSecurity: 'STARTTLS', imapHost: '', imapPort: 993, imapSecurity: 'SSL' }

export interface AccountsState {
  accounts: Account[]
  selectedId?: number
  draft: AccountDraft
  select: (id: number) => void
  setDraft: (value: AccountDraft) => void
  load: () => Promise<void>
  save: () => Promise<void>
}

export const useAccountsStore = create<AccountsState>((set, get) => ({
  accounts: [],
  selectedId: undefined,
  draft: initialAccountDraft,
  select: selectedId => set({ selectedId }),
  setDraft: draft => set({ draft: { ...draft } }),
  load: async () => {
    const result = await checked(rpc.email_accounts_list({}))
    const accounts = result.accounts ?? []
    set({ accounts, selectedId: get().selectedId ?? accounts.find(item => item.defaultAccount)?.id ?? accounts[0]?.id })
  },
  save: async () => {
    await checked(rpc.email_account_save({ ...get().draft }))
    set({ draft: { ...get().draft, password: '' } })
    await get().load()
  },
}))

/** The draft minus the write-only password — safe for display/logging. */
export const selectPublicDraft = (state: AccountsState): Omit<AccountDraft, 'password'> => {
  const { password: _password, ...safe } = state.draft
  return safe
}
