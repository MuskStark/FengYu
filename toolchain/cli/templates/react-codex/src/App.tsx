import { useState } from 'react'
import {
  Chip,
  FilePicker,
  GoldButton,
  Page,
  PageHeader,
  PluginBar,
  PluginShell,
  StatusBar,
  StatusChip,
  useFengYuClient,
  useFengYuNotify,
  type FileRef,
} from '@infinia/plugin-ui'

/**
 * {{pluginName}} — a FengYu plugin scaffolded by `fengyu init`.
 *
 * The canonical Infinia composition: the plugin shell, header, a host file
 * picker, and a hairline task table around a realistic "import and process
 * spreadsheets" workflow (T2 流水线 archetype). The worker is mocked in dev
 * until you add one.
 *
 * Plugin id: {{pluginId}}
 */
interface TaskRow {
  file: string
  rule: string
  state: 'done' | 'running' | 'queued'
}

const SEED_TASKS: TaskRow[] = [
  { file: '2026Q3_回款明细.xlsx', rule: '按 客户名称 列', state: 'done' },
  { file: '华东大区_库存.xlsx', rule: '按 仓库 工作表', state: 'queued' },
]

export default function App() {
  const client = useFengYuClient()
  const { notify } = useFengYuNotify()
  const [file, setFile] = useState<FileRef | null>(null)
  const [tasks, setTasks] = useState<TaskRow[]>(SEED_TASKS)

  async function enqueue(): Promise<void> {
    if (!file) return
    setTasks((rows) => [{ file: file.name, rule: '每 5000 行', state: 'running' }, ...rows])
    try {
      await client.invoke('process', { file: file.name })
      setTasks((rows) => rows.map((row) => (row.file === file.name ? { ...row, state: 'done' } : row)))
      await notify(`已处理 ${file.name}`, { tone: 'success' })
    } catch (error) {
      await notify(String(error), { tone: 'error' })
    }
  }

  return (
    <PluginShell>
      <PluginBar
        tabs={[{ value: 'tasks', title: '任务' }]}
        right={
          <>
            <StatusChip tone="idle">worker 未连接</StatusChip>
            <GoldButton data-action="enqueue" disabled={!file} onClick={() => void enqueue()}>
              开始处理
            </GoldButton>
          </>
        }
      />
      <Page fluid>
        <PageHeader
          title="导入并处理表格"
          description="T2 流水线原型：拖入或选择文件，任务表展示处理状态。"
        />
        <FilePicker value={file} onChange={setFile} extensions={['.xlsx', '.xls']} label="选择工作簿" />
        <div className="mt-4 overflow-hidden rounded-xl border border-line bg-panel">
          {tasks.map((task) => (
            <div
              key={task.file}
              data-task-row=""
              className="grid grid-cols-[minmax(0,2fr)_minmax(0,1.4fr)_88px] items-center gap-3 border-b border-line px-4 py-2.5 text-[13px] last:border-b-0"
            >
              <span className="truncate font-mono text-[12.5px]">{task.file}</span>
              <span className="truncate text-ink-2">{task.rule}</span>
              <span className="flex justify-end">
                <Chip>{task.state === 'done' ? '已完成' : task.state === 'running' ? '处理中' : '排队中'}</Chip>
              </span>
            </div>
          ))}
        </div>
      </Page>
      <StatusBar left={<span>mock worker</span>} right={<span>{{pluginId}}</span>} />
    </PluginShell>
  )
}
