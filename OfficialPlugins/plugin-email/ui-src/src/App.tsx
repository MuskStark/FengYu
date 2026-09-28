import { useEffect, type ComponentType, type ReactNode } from 'react'
import {
  GhostButton,
  PluginBar,
  PluginShell,
  StatusBar,
  StatusChip,
  useFengYuI18n,
  useFengYuNotify,
} from '@infinia/plugin-ui'
import {
  IconHistory,
  IconInbox,
  IconPencil,
  IconSend,
  IconSettings,
  IconUsers,
} from '@tabler/icons-react'
import ComposeTab from './components/ComposeTab'
import BatchTab from './components/BatchTab'
import AddressBookTab from './components/AddressBookTab'
import CollectTab from './components/CollectTab'
import SendRecordsView from './components/SendRecordsView'
import AccountSettingsView from './components/AccountSettingsView'
import { useAccountsStore } from './stores/accounts'
import { useNavigationStore, type WorkspaceId } from './stores/navigation'
import { PLUGIN_ID, PLUGIN_VERSION, actionable } from './sdk'

const WORKSPACES: Record<WorkspaceId, ComponentType> = {
  compose: ComposeTab,
  batch: BatchTab,
  contacts: AddressBookTab,
  archive: CollectTab,
  records: SendRecordsView,
  accounts: AccountSettingsView,
}

/** Tab glyphs — one Tabler glyph per workspace, inline in the focus bar pill. */
const WORKSPACE_ICONS: Record<WorkspaceId, ReactNode> = {
  compose: <IconPencil size={15} stroke={1.7} />,
  batch: <IconSend size={15} stroke={1.7} />,
  contacts: <IconUsers size={15} stroke={1.7} />,
  archive: <IconInbox size={15} stroke={1.7} />,
  records: <IconHistory size={15} stroke={1.7} />,
  accounts: <IconSettings size={15} stroke={1.7} />,
}

const TAB_LABEL_KEYS: Record<WorkspaceId, string> = {
  archive: 'nav.archive',
  compose: 'nav.compose',
  batch: 'nav.batch',
  contacts: 'nav.contacts',
  records: 'nav.records',
  accounts: 'nav.accounts',
}

/**
 * T3 中心台 shell（聚焦工作台版，无侧边栏）：六个工作区视图全部收进
 * 顶部聚焦条的胶囊；账户状态与同步动作在右侧。原来的侧栏轨道与
 * 页签条是同一语义的两层导航，合并为一层。
 */
export default function App() {
  const { t } = useFengYuI18n()
  const { notify } = useFengYuNotify()
  const items = useNavigationStore(state => state.items)
  const active = useNavigationStore(state => state.active)
  const setActive = useNavigationStore(state => state.setActive)
  const accounts = useAccountsStore(state => state.accounts)
  const loadAccounts = useAccountsStore(state => state.load)

  useEffect(() => {
    loadAccounts().catch(value => {
      void notify(actionable(value, t('accounts.loading')), { tone: 'error' })
    })
  }, [])  // mount-only: `t` is a fresh bound fn per render and must not be a dep

  const Work = WORKSPACES[active]
  const defaultAccount = accounts.find(account => account.defaultAccount) ?? accounts[0]

  return (
    <PluginShell>
      <PluginBar
        active={active}
        onNavigate={value => setActive(value as WorkspaceId)}
        tabs={items.map(item => ({
          value: item.id,
          title: t(item.labelKey),
          icon: WORKSPACE_ICONS[item.id],
        }))}
        right={
          <>
            <StatusChip tone={defaultAccount ? 'success' : 'idle'} data-testid="header-account">
              {defaultAccount ? defaultAccount.email : t('accounts.loading')}
            </StatusChip>
            <GhostButton onClick={() => void useAccountsStore.getState().load()}>{t('app.sync')}</GhostButton>
          </>
        }
      />

      <main className="flex min-w-0 flex-1 flex-col">
        <div className="email-workspace" data-workspace={active}>
          <Work key={active} />
        </div>

        <StatusBar
          left={
            <>
              <span>{defaultAccount ? defaultAccount.email : t('accounts.loading')}</span>
              <span>{t(TAB_LABEL_KEYS[active])}</span>
            </>
          }
          right={<span>{PLUGIN_ID} · {PLUGIN_VERSION}</span>}
        />
      </main>
    </PluginShell>
  )
}
