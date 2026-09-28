/* ================================================================
   模板总览 spec 页：四类差异化模板的体系图 + 设计令牌对照。
   这是设计文档页（非交互组件展示），用文档式规格卡呈现。
   ================================================================ */
import type { ReactNode } from 'react'
import { HexMark } from '@/components/infinia/chrome'
import { IconFileText, IconFileSpreadsheet, IconInbox, IconHammer } from '@tabler/icons-react'

const TEMPLATES = [
  {
    id: 'T1',
    name: '文档编辑类',
    category: 'text',
    icon: <IconFileText size={14} stroke={1.8} />,
    metaphor: '写作台',
    layout: '窄导航轨 + 文档栏 + 编辑/预览双栏',
    wireframe: 'rails-docs-split',
    accent: '沉浸优先 · 全幅画布',
    components: ['Sidebar', 'PlaceholdersAndVanishInput', 'CardSpotlight', 'TextGenerateEffect', 'FloatingDock'],
    sample: 'Markdown 编辑器',
  },
  {
    id: 'T2',
    name: '文件处理类',
    category: 'file',
    icon: <IconFileSpreadsheet size={14} stroke={1.8} />,
    metaphor: '流水线',
    layout: '导入区 + 流水线进度 + 任务表',
    wireframe: 'upload-pipeline-table',
    accent: '吞吐优先 · 进度可见',
    components: ['Sidebar', 'FileUpload', 'MultiStepLoader', 'GlowingEffect', 'Meteors'],
    sample: 'Excel 拆分器',
  },
  {
    id: 'T3',
    name: '沟通中心类',
    category: 'network',
    icon: <IconInbox size={14} stroke={1.8} />,
    metaphor: '中心工作台',
    layout: '动效页签 + 列表 ⇄ 阅读 + 发送队列',
    wireframe: 'tabs-list-detail-queue',
    accent: '往返优先 · 状态即时',
    components: ['Sidebar', 'Tabs', 'CardSpotlight', 'StatefulButton', 'MovingBorder', 'CardStack'],
    sample: '邮件中心',
  },
  {
    id: 'T4',
    name: '构建台类',
    category: 'dev',
    icon: <IconHammer size={14} stroke={1.8} />,
    metaphor: '构建台',
    layout: '配置列 + 构建状态卡 + 实时控制台',
    wireframe: 'config-build-console',
    accent: '过程优先 · mono 优先',
    components: ['Sidebar', 'Terminal', 'GlowingEffect', 'Meteors'],
    sample: '离线 Python 构建器',
  },
]

function Wireframe({ kind }: { kind: string }) {
  const bar = (w: string, filled?: string) => (
    <span className="inline-block h-1.5 rounded-full" style={{ width: w, background: filled ?? 'var(--c-line)' }} />
  )
  const box = (flex: string, h: string) => (
    <span className="inline-block rounded-sm" style={{ flex, height: h, background: 'var(--c-line)' }} />
  )
  return (
    <div className="flex h-24 items-stretch gap-1 rounded-lg border border-line bg-muted-surface p-2">
      {kind === 'rails-docs-split' && (
        <>
          <span className="flex w-3 flex-col gap-1">{box('1', '4px')}{box('1', '4px')}{box('1', '4px')}</span>
          <span className="flex w-8 flex-col gap-1 py-1">{box('1', '3px')}{box('1', '3px')}{box('1', '3px')}{box('1', '3px')}</span>
          <span className="flex flex-1 flex-col gap-1 py-1">{box('1', '4px')}{box('1', '100%')}{box('1', '100%')}</span>
          <span className="flex flex-1 flex-col gap-1 py-1">{box('1', '100%')}{box('1', '100%')}{box('1', '4px')}</span>
        </>
      )}
      {kind === 'upload-pipeline-table' && (
        <>
          <span className="flex flex-1 flex-col gap-1.5 py-1">
            {box('1', '18%')}
            {box('1', '100%')}
            {box('1', '100%')}
            {box('1', '100%')}
          </span>
          <span className="flex w-10 flex-col gap-1 py-1">
            {box('1', '3px')}{box('1', '3px')}{box('1', '3px')}
            <span className="inline-block h-1.5 overflow-hidden rounded-full" style={{ background: 'var(--c-line)' }}>
              <span className="block h-full w-3/5 rounded-full" style={{ background: 'var(--c-gold)' }} />
            </span>
          </span>
        </>
      )}
      {kind === 'tabs-list-detail-queue' && (
        <>
          <span className="flex w-9 flex-col gap-1 py-1">{box('1', '3px')}{box('1', '3px')}{box('1', '3px')}{box('1', '3px')}</span>
          <span className="flex flex-1 flex-col gap-1 py-1">{box('1', '100%')}{box('1', '100%')}{box('1', '100%')}</span>
          <span className="flex w-8 flex-col gap-1 py-1">
            <span className="flex h-8 flex-col gap-1 rounded-sm p-1" style={{ outline: '1.5px solid var(--c-gold)', outlineOffset: '-1.5px' }}>{box('1', '2px')}{box('1', '2px')}</span>
            <span className="flex h-8 flex-col gap-1 rounded-sm border border-line p-1">{box('1', '2px')}{box('1', '2px')}</span>
          </span>
        </>
      )}
      {kind === 'config-build-console' && (
        <>
          <span className="flex w-10 flex-col gap-1.5 py-1">
            {box('1', '12%')}{box('1', '16%')}{box('1', '30%')}{box('1', '16%')}
          </span>
          <span className="flex flex-1 flex-col gap-1.5 py-1">
            {box('1', '26%')}
            <span className="flex flex-1 flex-col gap-1 rounded-sm" style={{ background: '#111318' }}>{bar('70%', 'var(--c-gold)')}{bar('55%', 'var(--c-gold)')}{bar('80%', 'var(--c-gold)')}</span>
          </span>
        </>
      )}
    </div>
  )
}

const TOKENS: { label: string; children: ReactNode }[] = [
  {
    label: '画布 / 面板 / 弱面',
    children: (
      <span className="flex items-center gap-1.5">
        {['#faf9f6', '#ffffff', '#f3f1ec'].map((c) => (
          <span key={c} className="size-6 rounded-md border border-line" style={{ background: c }} />
        ))}
        <span className="font-mono text-[11px] text-ink-3">暖白 → 白 → 暖灰</span>
      </span>
    ),
  },
  {
    label: '发丝线',
    children: (
      <span className="flex items-center gap-1.5">
        <span className="h-px w-16 bg-line-strong" />
        <span className="font-mono text-[11px] text-ink-3">#e5e2db · 1px · 不用投影分层</span>
      </span>
    ),
  },
  {
    label: '金（唯一高饱和）',
    children: (
      <span className="flex items-center gap-1.5">
        <span className="size-6 rounded-md" style={{ background: 'var(--c-gold)' }} />
        <span className="font-mono text-[11px] text-ink-3">#eab04b · 深墨字 #18181b · 暗色 #f6bd60</span>
      </span>
    ),
  },
  {
    label: '蜂巢印记',
    children: (
      <span className="flex items-center gap-2">
        <HexMark size={20}>
          <IconFileText size={11} stroke={2} />
        </HexMark>
        <span className="font-mono text-[11px] text-ink-3">六边形 · 金底墨标 · 账户/插件统一</span>
      </span>
    ),
  },
  {
    label: '选中态',
    children: <span className="font-mono text-[11px] text-ink-3">中性胶囊 + 2px 金色内嵌条（绝不只靠字重）</span>,
  },
  {
    label: '圆角 / 字阶',
    children: <span className="font-mono text-[11px] text-ink-3">卡片 12 · 控件 8 · 标签全圆 · 正文 14 / 辅助 12 / mono 11.5</span>,
  },
]

export default function Overview() {
  return (
    <div className="min-h-screen bg-canvas text-ink">
      <div className="mx-auto max-w-[1080px] px-10 py-14">
        <header className="mb-12">
          <div className="mb-4 flex items-center gap-3">
            <HexMark size={30}>
              <IconHammer size={16} stroke={2} />
            </HexMark>
            <div>
              <h1 className="text-2xl font-semibold tracking-tight">插件标准 UI 模板</h1>
              <p className="mt-0.5 text-sm text-ink-2">四类差异化 · Infinia 设计语言 · Aceternity 官方组件</p>
            </div>
          </div>
          <p className="max-w-[68ch] text-[13.5px] leading-relaxed text-ink-2">
            所有插件面板与主程序共享同一套令牌（暖白画布、白面板、发丝线、金色唯一高饱和交互色、蜂巢印记），
            在同一导航壳与状态条骨架上，按 manifest 类目选择四类布局原型之一。
            交互组件全部来自 ui.aceternity.com 官方源码，模板只做换肤与编排。
          </p>
        </header>

        {/* 四类模板规格卡 */}
        <div className="mb-14 grid grid-cols-2 gap-4">
          {TEMPLATES.map((t) => (
            <div key={t.id} className="rounded-xl border border-line bg-panel p-5">
              <div className="mb-3 flex items-center gap-2.5">
                <HexMark size={24}>{t.icon}</HexMark>
                <div className="min-w-0">
                  <div className="flex items-baseline gap-2">
                    <span className="font-mono text-[11px] text-ink-3">{t.id}</span>
                    <span className="text-[15px] font-semibold tracking-tight">{t.name}</span>
                    <span className="rounded-full bg-tag px-2 py-0.5 font-mono text-[10.5px] text-ink-2">{t.category}</span>
                  </div>
                  <div className="text-xs text-ink-2">
                    {t.metaphor} · 代表插件 {t.sample}
                  </div>
                </div>
              </div>
              <Wireframe kind={t.wireframe} />
              <div className="mt-3 text-[13px] text-ink">{t.layout}</div>
              <div className="mt-0.5 text-xs text-ink-2">{t.accent}</div>
              <div className="mt-3 flex flex-wrap gap-1.5">
                {t.components.map((c) => (
                  <span key={c} className="rounded-full bg-tag px-2 py-0.5 font-mono text-[10.5px] text-ink-2">
                    {c}
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>

        {/* 令牌对照 */}
        <section className="mb-14">
          <h2 className="mb-4 text-lg font-semibold tracking-tight">设计令牌</h2>
          <div className="overflow-hidden rounded-xl border border-line bg-panel">
            {TOKENS.map((row, i) => (
              <div key={row.label} className={'flex items-center gap-6 px-5 py-3 ' + (i > 0 ? 'border-t border-line' : '')}>
                <span className="w-40 shrink-0 text-[13px] font-medium">{row.label}</span>
                <span className="min-w-0">{row.children}</span>
              </div>
            ))}
          </div>
        </section>

        {/* 类目 → 模板映射 */}
        <section className="mb-10">
          <h2 className="mb-4 text-lg font-semibold tracking-tight">类目映射</h2>
          <div className="overflow-hidden rounded-xl border border-line bg-panel text-[13px]">
            <div className="grid grid-cols-[120px_130px_minmax(0,1fr)_minmax(0,1.6fr)] gap-4 border-b border-line bg-muted-surface px-5 py-2.5 text-xs text-ink-2">
              <span>manifest 类目</span>
              <span>原型</span>
              <span>布局骨架</span>
              <span>差异化要点</span>
            </div>
            {[
              ['text', 'T1 写作台', '导航轨 + 文档栏 + 编辑/预览双栏', '沉浸画布、底部格式坞、预览金色追光'],
              ['file', 'T2 流水线', '导入 + 流水线 + 任务表', '拖拽导入、阶段加载器、运行任务流光描边'],
              ['network', 'T3 中心台', '动效页签 + 列表 ⇄ 阅读 + 队列', '页签胶囊动效、三态发送钮、队列卡片堆'],
              ['dev', 'T4 构建台', '配置列 + 状态卡 + 控制台', 'mono 优先、打字机控制台、阶段时间轴'],
            ].map((row, i) => (
              <div key={row[0]} className={'grid grid-cols-[120px_130px_minmax(0,1fr)_minmax(0,1.6fr)] gap-4 px-5 py-2.5 ' + (i > 0 ? 'border-t border-line' : '')}>
                <span className="font-mono text-xs text-ink-2">{row[0]}</span>
                <span>{row[1]}</span>
                <span className="text-ink-2">{row[2]}</span>
                <span className="text-ink-2">{row[3]}</span>
              </div>
            ))}
          </div>
          <p className="mt-3 text-xs text-ink-3">
            未识别类目默认回退 T2 流水线；模板在 fyp init 脚手架阶段按 category 选择。
          </p>
        </section>
      </div>
    </div>
  )
}
