/* ================================================================
   T2 · 文件处理类（category: file）— 代表插件：Excel 拆分器
   骨架隐喻「流水线」：导入区 → 流水线进度 → 任务表。
   Aceternity：Sidebar · FileUpload(拖拽导入) · MultiStepLoader(流水线)
   · GlowingEffect(运行中任务的流光描边) · Meteors(处理中)
   ================================================================ */
import { useState } from 'react'
import {
  IconFileSpreadsheet,
  IconHistory,
  IconSettings2,
  IconShieldCheck,
  IconPlus,
  IconPaperclip,
} from '@tabler/icons-react'
import { FileUpload } from '@/components/aceternity/file-upload'
import { MultiStepLoader } from '@/components/aceternity/multi-step-loader'
import { GlowingEffect } from '@/components/aceternity/glowing-effect'
import { Meteors } from '@/components/aceternity/meteors'
import { PluginShell, PluginHeader, StatusBar, StatusChip, GhostButton, GoldButton } from '@/components/infinia/chrome'

const PIPELINE = [
  { text: '解析工作簿结构' },
  { text: '按列值分片' },
  { text: '生成子工作簿' },
  { text: '写入导出目录' },
]

const TASKS = [
  { file: '2026Q3_回款明细.xlsx', rule: '按 客户名称 列', sheets: 14, state: '运行中', progress: 62 },
  { file: '华东大区_库存.xlsx', rule: '按 仓库 工作表', sheets: 8, state: '排队中', progress: 0 },
  { file: '供应商对账_9月.xlsx', rule: '每 5000 行', sheets: 5, state: '已完成', progress: 100 },
  { file: '年会预算_v3.xlsx', rule: '按 部门 列', sheets: 3, state: '已完成', progress: 100 },
]

export default function T2Excel() {
  const [files, setFiles] = useState<File[]>([])
  return (
    <PluginShell
      expanded
      brand={{ name: 'Excel 拆分器', icon: <IconFileSpreadsheet size={13} stroke={1.8} /> }}
      nav={[
        { label: '拆分任务', icon: <IconFileSpreadsheet size={19} stroke={1.6} />, active: true },
        { label: '拆分历史', icon: <IconHistory size={19} stroke={1.6} /> },
        { label: '运行设置', icon: <IconSettings2 size={19} stroke={1.6} /> },
        { label: '权限', icon: <IconShieldCheck size={19} stroke={1.6} /> },
      ]}
    >
      <main className="flex min-w-0 flex-1 flex-col">
        <PluginHeader
          icon={<IconFileSpreadsheet size={14} stroke={1.8} />}
          name="Excel 拆分器"
          category="file"
          version="v4.1.0"
          id="fan.summer.excel"
          right={
            <>
              <StatusChip tone="warning">处理中 2 项</StatusChip>
              <GhostButton>拆分规则</GhostButton>
              <GoldButton>
                <IconPlus size={15} stroke={1.7} />
                新建拆分
              </GoldButton>
            </>
          }
        />

        <div className="min-h-0 flex-1 overflow-hidden px-6 py-5">
          {/* 指标行：白卡 + mono 数字 */}
          <div className="mb-5 grid grid-cols-3 gap-4">
            {[
              { label: '本周拆分文件', value: '238' },
              { label: '输出子工作簿', value: '1,962' },
              { label: '平均耗时', value: '4.6 s' },
            ].map((s) => (
              <div key={s.label} className="rounded-xl border border-line bg-panel px-4 py-3">
                <div className="text-xs text-ink-2">{s.label}</div>
                <div className="mt-1 font-mono text-xl font-semibold tracking-tight text-ink">{s.value}</div>
              </div>
            ))}
          </div>

          <div className="grid min-h-0 grid-cols-[minmax(0,1fr)_300px] gap-4">
            {/* 左：导入 + 任务表 */}
            <div className="flex min-h-0 flex-col gap-4">
              <FileUpload onChange={setFiles} />

              <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-line bg-panel">
                <div className="grid grid-cols-[minmax(0,2.2fr)_minmax(0,1.4fr)_64px_88px] items-center gap-3 border-b border-line bg-muted-surface px-4 py-2 text-xs text-ink-2">
                  <span>文件</span>
                  <span>拆分方式</span>
                  <span className="text-right">工作表</span>
                  <span className="text-right">状态</span>
                </div>
                {TASKS.map((task) => {
                  const running = task.state === '运行中'
                  const done = task.state === '已完成'
                  return (
                    <div key={task.file} className="relative grid grid-cols-[minmax(0,2.2fr)_minmax(0,1.4fr)_64px_88px] items-center gap-3 border-b border-line px-4 py-2.5 text-[13px] last:border-b-0">
                      {running && (
                        <div className="pointer-events-none absolute inset-0 rounded-lg">
                          <GlowingEffect spread={40} borderWidth={1.5} glow="rgba(234,176,75,0.55)" variant="reduced" />
                        </div>
                      )}
                      <span className="flex min-w-0 items-center gap-2">
                        <IconPaperclip size={14} stroke={1.6} className="shrink-0 text-ink-3" />
                        <span className="truncate font-mono text-[12.5px]">{task.file}</span>
                      </span>
                      <span className="truncate text-ink-2">{task.rule}</span>
                      <span className="text-right font-mono text-ink-2">{task.sheets}</span>
                      <span className="flex justify-end">
                        {running && (
                          <span className="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px]" style={{ background: 'var(--c-warning-bg)', color: 'var(--c-warning)' }}>
                            <span className="inline-block h-1 w-6 overflow-hidden rounded-full" style={{ background: 'rgba(24,24,27,0.10)' }}>
                              <span className="block h-full rounded-full" style={{ width: `${task.progress}%`, background: 'var(--c-warning)' }} />
                            </span>
                            {task.progress}%
                          </span>
                        )}
                        {done && (
                          <span className="rounded-full px-2 py-0.5 text-[11px]" style={{ background: 'var(--c-success-bg)', color: 'var(--c-success)' }}>
                            已完成
                          </span>
                        )}
                        {task.state === '排队中' && (
                          <span className="rounded-full bg-tag px-2 py-0.5 text-[11px] text-ink-2">排队中</span>
                        )}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>

            {/* 右：流水线（官方 MultiStepLoader 嵌卡片） + 处理中 */}
            <div className="flex min-h-0 flex-col gap-4">
              <div className="rounded-xl border border-line bg-panel p-4">
                <div className="mb-1 flex items-center justify-between">
                  <span className="text-[13px] font-semibold">拆分流水线</span>
                  <span className="font-mono text-[11px] text-ink-3">2026Q3_回款明细.xlsx</span>
                </div>
                <div className="relative h-[192px] overflow-hidden">
                  <div className="-mt-[132px]">
                    <MultiStepLoader loadingStates={PIPELINE} loading duration={1500} loop={false} />
                  </div>
                </div>
              </div>

              {/* 处理中：官方 Meteors 流星 */}
              <div className="relative min-h-[120px] flex-1 overflow-hidden rounded-xl border border-line bg-panel">
                <Meteors number={14} />
                <div className="relative z-10 flex h-full flex-col justify-center px-4 py-4">
                  <span className="text-[13px] font-semibold">处理中</span>
                  <span className="mt-1 text-xs text-ink-2">14 个工作表已解析，正在按客户名称分片</span>
                  <span className="mt-3 font-mono text-[11px] text-ink-3">预计剩余 7 秒</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <StatusBar
          left={
            <>
              <span>工作进程 运行中</span>
              <span>任务 2 运行 · 1 排队</span>
            </>
          }
          right={<span>fan.summer.excel · v4.1.0</span>}
        />
      </main>
    </PluginShell>
  )
}
