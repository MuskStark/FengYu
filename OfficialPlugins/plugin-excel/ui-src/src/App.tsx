import { useState } from 'react'
import { PluginBar, PluginShell, useFengYuI18n } from '@infinia/plugin-ui'
import { ExcelSplitter } from './ExcelSplitter'

/**
 * T2 流水线 shell（聚焦工作台版，无侧边栏）：视图切换在顶部聚焦条的
 * 胶囊里（拆分任务 / 拆分历史 / 运行设置 / 权限）。
 * `ExcelSplitter` owns the stateful main column (header + pages + statusbar) because every
 * view renders splitter state; it stays mounted behind the other views so wizard
 * progress survives switches.
 */
export default function App() {
  const { t } = useFengYuI18n()
  const [nav, setNav] = useState('split')
  return (
    <PluginShell>
      <PluginBar
        active={nav}
        onNavigate={setNav}
        tabs={[
          { value: 'split', title: t('exui.nav.split') },
          { value: 'history', title: t('exui.nav.history') },
          { value: 'settings', title: t('exui.nav.settings') },
          { value: 'permissions', title: t('exui.nav.permissions') },
        ]}
      />
      <ExcelSplitter nav={nav} />
    </PluginShell>
  )
}
