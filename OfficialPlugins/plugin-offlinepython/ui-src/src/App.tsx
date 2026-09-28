import { useCallback, useState } from 'react'
import type { FileRef } from '@infinia/plugin-sdk'
import {
  GhostButton,
  PluginBar,
  PluginShell,
  StatusBar,
  StatusChip,
  useFengYuI18n,
  useFengYuNotify,
} from '@infinia/plugin-ui'
import { IconPlayerStop } from '@tabler/icons-react'
import ProjectPanel from './panels/ProjectPanel'
import DeployPanel from './panels/DeployPanel'
import DoctorPanel from './panels/DoctorPanel'
import type { PanelActivity } from './jobState'
import './styles.css'

// Mirrors manifest.base.json (id/version) — the status bar shows them.
const PLUGIN_ID = 'fan.summer.offlinepython'
const PLUGIN_VERSION = 'v4.1.0-alpha.1'

/**
 * T4 构建台 shell（聚焦工作台版，无侧边栏）：项目/部署/诊断视图在顶部
 * 聚焦条的胶囊里切换；运行状态与停止动作占据右侧槽位。
 */
export default function App() {
  const { t } = useFengYuI18n()
  const { notify } = useFengYuNotify()
  const [active, setActive] = useState('project')
  // Shared project directory (writable FileRef granted by the host). Selected from
  // the Project panel and read by the status bar — only shared state lives here.
  const [project, setProject] = useState<FileRef | null>(null)
  const [activity, setActivity] = useState<PanelActivity | null>(null)

  const toast = useCallback((message: string) => { void notify(message) }, [notify])
  const reportActivity = useCallback((next: PanelActivity | null) => setActivity(next), [])

  const panelTitle =
    active === 'project' ? t('opb.nav.project') : active === 'deploy' ? t('opb.nav.deploy') : t('opb.nav.doctor')

  return (
    <PluginShell>
      <PluginBar
        active={active}
        onNavigate={setActive}
        tabs={[
          { value: 'project', title: t('opb.nav.project') },
          { value: 'deploy', title: t('opb.nav.deploy') },
          { value: 'doctor', title: t('opb.nav.doctor') },
        ]}
        right={
          activity ? (
            <>
              <StatusChip tone={activity.tone}>{activity.label}</StatusChip>
              {activity.running && activity.cancel ? (
                <GhostButton className="opb-danger" onClick={activity.cancel}>
                  <IconPlayerStop size={14} stroke={1.7} />
                  {t('opb.build.cancel')}
                </GhostButton>
              ) : null}
            </>
          ) : null
        }
      />

      <main className="opb-main">
        {active === 'project' ? (
          <ProjectPanel project={project} onProject={setProject} toast={toast} onActivity={reportActivity} />
        ) : active === 'deploy' ? (
          <DeployPanel toast={toast} onActivity={reportActivity} />
        ) : (
          <DoctorPanel toast={toast} />
        )}

        <StatusBar
          left={
            <>
              <span>{project ? project.name : t('opb.project.empty')}</span>
              <span>{panelTitle}</span>
              {activity ? <span>{activity.label}</span> : null}
            </>
          }
          right={<span>{`${PLUGIN_ID} · ${PLUGIN_VERSION}`}</span>}
        />
      </main>
    </PluginShell>
  )
}
