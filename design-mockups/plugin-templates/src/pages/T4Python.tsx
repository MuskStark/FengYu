/* ================================================================
   T4 · 构建台类（category: dev）— 代表插件：离线 Python 构建器
   骨架隐喻「构建台」：配置列 + 构建状态卡 + 实时控制台（深色对比面板）。
   Aceternity：Sidebar · Terminal(打字机控制台) · GlowingEffect(运行中流光)
   · Meteors(构建中流星)
   差异化：mono 字体优先、阶段时间轴、控制台作为深色对比面板
   （Infinia 规则内的唯一深色面板——控制台语义）。
   ================================================================ */
import {
  IconHammer,
  IconPackage,
  IconStack2,
  IconSettings,
  IconFolderOpen,
  IconPlayerStop,
  IconCheck,
} from '@tabler/icons-react'
import { Terminal } from '@/components/aceternity/terminal'
import { GlowingEffect } from '@/components/aceternity/glowing-effect'
import { Meteors } from '@/components/aceternity/meteors'
import { PluginShell, PluginHeader, StatusBar, StatusChip, GhostButton, GoldButton } from '@/components/infinia/chrome'

const STAGES = [
  { name: '解析依赖', time: '00:03', state: 'done' },
  { name: '下载 wheels', time: '00:41', state: 'running' },
  { name: '校验哈希', time: '', state: 'pending' },
  { name: '生成离线清单', time: '', state: 'pending' },
]

const PACKAGES = ['numpy', 'pandas', 'openpyxl', 'requests', 'pyinstaller']

export default function T4Python() {
  return (
    <PluginShell
      expanded={false}
      brand={{ name: '离线 Python 构建器', icon: <IconHammer size={13} stroke={1.8} /> }}
      nav={[
        { label: '构建', icon: <IconHammer size={19} stroke={1.6} />, active: true },
        { label: '镜像仓库', icon: <IconPackage size={19} stroke={1.6} /> },
        { label: '依赖组', icon: <IconStack2 size={19} stroke={1.6} /> },
        { label: '设置', icon: <IconSettings size={19} stroke={1.6} /> },
      ]}
    >
      <main className="flex min-w-0 flex-1 flex-col">
        <PluginHeader
          icon={<IconHammer size={14} stroke={1.8} />}
          name="离线 Python 构建器"
          category="dev"
          version="v4.1.0"
          id="fan.summer.offlinepython"
          right={
            <>
              <StatusChip tone="warning">构建 #482 运行中</StatusChip>
              <GhostButton className="!text-danger">
                <IconPlayerStop size={14} stroke={1.7} />
                停止构建
              </GhostButton>
            </>
          }
        />

        <div className="grid min-h-0 flex-1 grid-cols-[320px_minmax(0,1fr)] gap-4 px-5 py-4">
          {/* 左：构建配置 */}
          <section className="flex min-h-0 flex-col gap-4 overflow-hidden">
            <div className="rounded-xl border border-line bg-panel p-4">
              <div className="mb-2 text-[13px] font-semibold">Python 版本</div>
              <div className="flex gap-1 rounded-lg bg-muted-surface p-1">
                {['3.12.7', '3.11.9', '3.10.13'].map((v, i) => (
                  <button
                    key={v}
                    type="button"
                    className={
                      'h-7 flex-1 rounded-md font-mono text-xs transition-colors ' +
                      (i === 0 ? 'bg-panel text-ink shadow-[inset_0_0_0_1px_var(--c-line)]' : 'text-ink-2 hover:text-ink')
                    }
                  >
                    {v}
                  </button>
                ))}
              </div>
            </div>

            <div className="rounded-xl border border-line bg-panel p-4">
              <div className="mb-2 text-[13px] font-semibold">目标平台</div>
              <div className="flex flex-wrap gap-1.5">
                {[
                  ['win-amd64', true],
                  ['macos-arm64', false],
                  ['manylinux', false],
                ].map(([p, active]) => (
                  <span
                    key={p as string}
                    className={
                      'rounded-full px-2.5 py-1 font-mono text-[11px] ' +
                      (active ? 'bg-gold text-gold-ink' : 'bg-tag text-ink-2')
                    }
                  >
                    {p}
                  </span>
                ))}
              </div>
            </div>

            <div className="flex min-h-0 flex-1 flex-col rounded-xl border border-line bg-panel p-4">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-[13px] font-semibold">依赖包</span>
                <span className="font-mono text-[11px] text-ink-3">47 · 含传递依赖</span>
              </div>
              <div className="flex flex-wrap gap-1.5 overflow-hidden">
                {PACKAGES.map((p) => (
                  <span key={p} className="rounded-full bg-tag px-2.5 py-1 font-mono text-[11px] text-ink-2">
                    {p}
                  </span>
                ))}
                <span className="rounded-full bg-tag px-2.5 py-1 font-mono text-[11px] text-ink-3">…42 more</span>
              </div>
            </div>

            <div className="rounded-xl border border-line bg-panel p-4">
              <div className="mb-2 text-[13px] font-semibold">导出目录</div>
              <div className="flex items-center gap-2">
                <code className="min-w-0 flex-1 truncate rounded-lg bg-muted-surface px-2.5 py-1.5 font-mono text-xs text-ink-2">
                  /Volumes/Repo/py312-win-offline
                </code>
                <GhostButton className="!px-2.5">
                  <IconFolderOpen size={14} stroke={1.6} />
                </GhostButton>
              </div>
            </div>

            <GoldButton className="justify-center">
              <IconHammer size={15} stroke={1.7} />
              再次构建
            </GoldButton>
          </section>

          {/* 右：构建状态 + 控制台 */}
          <section className="flex min-h-0 flex-col gap-4">
            {/* 构建状态卡：官方 GlowingEffect 流光描边 + Meteors */}
            <div className="relative overflow-hidden rounded-xl border border-line bg-panel">
              <GlowingEffect spread={60} borderWidth={1.5} glow="rgba(234,176,75,0.5)" variant="reduced" disabled={false} />
              <Meteors number={10} />
              <div className="relative z-10 flex items-stretch">
                <div className="min-w-0 flex-1 px-5 py-4">
                  <div className="flex items-center gap-3">
                    <span className="text-[14px] font-semibold tracking-tight">构建 #482</span>
                    <span className="font-mono text-xs text-ink-3">python-3.12.7 · win-amd64 · 47 包</span>
                  </div>
                  <div className="mt-3 flex gap-6 font-mono text-xs text-ink-2">
                    <span>
                      已下载 <span className="text-ink">213 MB</span>
                    </span>
                    <span>
                      速度 <span className="text-ink">18.4 MB/s</span>
                    </span>
                    <span>
                      剩余 <span className="text-ink">24 个</span>
                    </span>
                  </div>
                </div>
                {/* 阶段时间轴（静态模板内容） */}
                <div className="w-[240px] shrink-0 border-l border-line px-5 py-4">
                  {STAGES.map((s) => (
                    <div key={s.name} className="flex items-center gap-2.5 py-1.5 text-[13px]">
                      {s.state === 'done' && (
                        <span className="grid size-4 place-items-center rounded-full" style={{ background: 'var(--c-success-bg)', color: 'var(--c-success)' }}>
                          <IconCheck size={11} stroke={2.4} />
                        </span>
                      )}
                      {s.state === 'running' && (
                        <span className="size-2 animate-pulse rounded-full bg-gold ring-4 ring-gold/20" />
                      )}
                      {s.state === 'pending' && <span className="size-2 rounded-full border border-line-strong" />}
                      <span className={s.state === 'running' ? 'text-ink' : s.state === 'done' ? 'text-ink-2' : 'text-ink-3'}>
                        {s.name}
                      </span>
                      {s.time && <span className="ml-auto font-mono text-[11px] text-ink-3">{s.time}</span>}
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* 控制台：官方 Terminal（组件自带深色卡——控制台语义的唯一深色面板） */}
            <div className="relative min-h-0 flex-1 overflow-hidden rounded-xl border border-line bg-panel p-4">
              <div className="mb-2 flex items-center gap-2">
                <span className="text-[13px] font-semibold">构建控制台</span>
                <span className="font-mono text-[11px] text-ink-3">stdout · 实时</span>
              </div>
              <div className="h-[368px] overflow-hidden">
                <Terminal
                  username="fengyu-builder"
                  commands={['fyp build --profile py312-win --offline']}
                  outputs={{
                    0: [
                      'resolving 47 packages (incl. transitive)…',
                      'downloading numpy-2.1.3-cp312-cp312-win_amd64.whl  [12.6 MB]',
                      'downloading pandas-2.2.3-cp312-cp312-win_amd64.whl  [11.3 MB]',
                      '23/47 wheels fetched · 18.4 MB/s · eta 41s',
                    ],
                  }}
                  typingSpeed={40}
                  delayBetweenCommands={600}
                  enableSound={false}
                  className="!max-w-none w-full"
                />
              </div>
            </div>
          </section>
        </div>

        <StatusBar
          left={
            <>
              <span>构建进程 运行中</span>
              <span>阶段 2/4 · 下载 wheels</span>
              <span>磁盘 1.8 GB</span>
            </>
          }
          right={<span>fan.summer.offlinepython · v4.1.0</span>}
        />
      </main>
    </PluginShell>
  )
}
