import { useState } from 'react'
import {
  Chip,
  ConfirmDialog,
  ErrorState,
  GhostButton,
  GoldButton,
  Select,
  StatusChip,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { useAccountsStore, type AccountDraft } from '../stores/accounts'
import { actionable, checked, rpc } from '../sdk'

const SECURITY_OPTIONS = ['SSL', 'STARTTLS', 'PLAIN']

function newAccountDraft(): AccountDraft {
  return { displayName: '', email: '', password: '', smtpHost: '', smtpPort: 587,
    smtpSecurity: 'STARTTLS', smtpSkipCertVerify: false, imapHost: '', imapPort: 993,
    imapSecurity: 'SSL', imapSkipCertVerify: false, defaultAccount: false }
}

export default function AccountSettingsView() {
  const { t } = useFengYuI18n()
  const accounts = useAccountsStore(state => state.accounts)
  const draft = useAccountsStore(state => state.draft)
  const setDraft = useAccountsStore(state => state.setDraft)
  const load = useAccountsStore(state => state.load)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [deleteDialog, setDeleteDialog] = useState(false)

  function patch(partial: Partial<AccountDraft>): void {
    setDraft({ ...useAccountsStore.getState().draft, ...partial })
  }

  async function guard(action: string, task: () => Promise<void>): Promise<void> {
    setBusy(true); setError(''); setNotice('')
    try { await task() } catch (value) { setError(actionable(value, action)) }
    finally { setBusy(false) }
  }

  function newAccount(): void { setDraft(newAccountDraft()) }

  function editAccount(account: Parameters<typeof setDraft>[0]): void {
    const { password: _password, ...rest } = account as AccountDraft & { passwordConfigured?: boolean }
    void _password
    setDraft({ ...newAccountDraft(), ...rest })
  }

  const testAccount = () => guard(t('accounts.testAction'), async () => {
    await checked(rpc.email_account_test({ accountId: draft.id! })); setNotice(t('accounts.testSuccess'))
  })
  const testImapAccount = () => guard(t('accounts.testAction'), async () => {
    await checked(rpc.email_account_test_imap({ accountId: draft.id! })); setNotice(t('accounts.testSuccess'))
  })
  const saveAccount = () => guard(t('accounts.saveAction'), async () => {
    await checked(rpc.email_account_save({ ...draft })); patch({ password: '' }); await load(); setNotice(t('accounts.saved'))
  })
  const makeDefault = () => guard(t('accounts.defaultAction'), async () => {
    await checked(rpc.email_account_set_default({ id: draft.id! })); await load()
  })

  const removeAccount = () => { if (draft.id) setDeleteDialog(true) }
  const confirmRemoveAccount = () => {
    if (!draft.id) return
    void guard(t('accounts.deleteAction'), async () => {
      await checked(rpc.email_account_delete({ id: draft.id! })); newAccount(); await load()
    })
  }

  return (
    <section className="fy-card account-card">
      <header className="fy-card-title"><h2>{t('accounts.title')}</h2></header>
      <div className="fy-card-body">
        {error ? <ErrorState title={t('errors.unknown')} message={error} className="mb-4" /> : null}
        {notice ? <p className="email-alert email-alert--success">{notice}</p> : null}
        <div className="account-layout">
          <div className="account-list">
            {accounts.map(account => (
              <button key={account.id} type="button" className="account-list-item" onClick={() => editAccount(account)}>
                <span className="account-list-name">{account.displayName}</span>
                <span className="account-list-email">{account.email}</span>
                {account.defaultAccount ? <Chip className="tag-pill">{t('accounts.defaultAccount')}</Chip> : null}
              </button>
            ))}
            <button type="button" className="account-list-item" data-testid="account-new" onClick={newAccount}>
              <span className="account-list-name">{t('accounts.newAccount')}</span>
            </button>
          </div>
          <div>
            <label className="email-field">
              <span>{t('accounts.displayName')}</span>
              <input className="email-input" value={draft.displayName} onChange={event => patch({ displayName: event.target.value })} />
            </label>
            <label className="email-field">
              <span>{t('contacts.email')}</span>
              <input className="email-input" type="email" value={draft.email} onChange={event => patch({ email: event.target.value })} />
            </label>
            <label className="email-field">
              <span>{t('accounts.password')}</span>
              <input className="email-input" type="password" autoComplete="new-password" data-testid="account-password"
                value={draft.password} onChange={event => patch({ password: event.target.value })} />
              <span className="email-hint email-hint--caption">{t('accounts.passwordHelp')}</span>
            </label>

            <fieldset className="account-section">
              <legend>{t('accounts.smtpSection')}</legend>
              <div className="form-grid">
                <label className="email-field full-row">
                  <span>{t('accounts.smtp')}</span>
                  <input className="email-input" value={draft.smtpHost} onChange={event => patch({ smtpHost: event.target.value })} />
                </label>
                <label className="email-field">
                  <span>{t('accounts.port')}</span>
                  <input className="email-input" type="number" value={draft.smtpPort} onChange={event => patch({ smtpPort: Number(event.target.value) })} />
                </label>
                <label className="email-field">
                  <span>{t('accounts.security')}</span>
                  <Select className="email-select" value={draft.smtpSecurity ?? ''}
                    onChange={value => patch({ smtpSecurity: value })}
                    options={SECURITY_OPTIONS.map(option => ({ value: String(option), label: String(option) }))} />
                </label>
              </div>
              <div className="account-section-actions">
                <GhostButton data-testid="smtp-test" disabled={busy} onClick={testAccount}>{t('accounts.testSmtp')}</GhostButton>
              </div>
              <label className="account-switch">
                <input type="checkbox" checked={Boolean(draft.smtpSkipCertVerify)} onChange={event => patch({ smtpSkipCertVerify: event.target.checked })} />
                <span>{t('accounts.skipCert')}</span>
              </label>
              {draft.smtpSkipCertVerify ? <StatusChip tone="warning">{t('accounts.skipCertWarn')}</StatusChip> : null}
            </fieldset>

            <fieldset className="account-section">
              <legend>{t('accounts.imapSection')}</legend>
              <div className="form-grid">
                <label className="email-field full-row">
                  <span>{t('accounts.imap')}</span>
                  <input className="email-input" value={draft.imapHost} onChange={event => patch({ imapHost: event.target.value })} />
                </label>
                <label className="email-field">
                  <span>{t('accounts.port')}</span>
                  <input className="email-input" type="number" value={draft.imapPort} onChange={event => patch({ imapPort: Number(event.target.value) })} />
                </label>
                <label className="email-field">
                  <span>{t('accounts.security')}</span>
                  <Select className="email-select" value={draft.imapSecurity ?? ''}
                    onChange={value => patch({ imapSecurity: value })}
                    options={SECURITY_OPTIONS.map(option => ({ value: String(option), label: String(option) }))} />
                </label>
              </div>
              <div className="account-section-actions">
                <GhostButton data-testid="imap-test" disabled={busy} onClick={testImapAccount}>{t('accounts.testImap')}</GhostButton>
              </div>
              <label className="account-switch">
                <input type="checkbox" checked={Boolean(draft.imapSkipCertVerify)} onChange={event => patch({ imapSkipCertVerify: event.target.checked })} />
                <span>{t('accounts.skipCert')}</span>
              </label>
              {draft.imapSkipCertVerify ? <StatusChip tone="warning">{t('accounts.skipCertWarn')}</StatusChip> : null}
            </fieldset>

            <label className="account-switch">
              <input type="checkbox" checked={Boolean(draft.defaultAccount)} onChange={event => patch({ defaultAccount: event.target.checked })} />
              <span>{t('accounts.defaultAccount')}</span>
            </label>
            <div className="account-actions">
              {draft.id ? <button type="button" className="email-danger-button" onClick={removeAccount}>{t('common.delete')}</button> : null}
              {draft.id && !draft.defaultAccount ? <GhostButton onClick={makeDefault}>{t('accounts.makeDefault')}</GhostButton> : null}
              <GoldButton data-testid="account-save" disabled={busy} onClick={saveAccount}>
                {busy ? t('common.loading') : t('common.save')}
              </GoldButton>
            </div>
          </div>
        </div>
      </div>

      <ConfirmDialog
        open={deleteDialog}
        title={t('accounts.deleteAction')}
        message={t('accounts.deleteConfirm')}
        confirmLabel={t('common.delete')}
        destructive
        onConfirm={confirmRemoveAccount}
        onCancel={() => setDeleteDialog(false)}
      />
    </section>
  )
}
