/* ================================================================
   T1 · 文档编辑类（category: text）— 代表插件：Markdown 编辑器
   骨架隐喻「写作台」：窄导航轨 + 文档栏 + 双栏编辑/预览。
   Aceternity：Sidebar(hover 展开) · PlaceholdersAndVanishInput(检索)
   · CardSpotlight(预览卡) · TextGenerateEffect(标题生成) · FloatingDock(格式坞)
   ================================================================ */
import { useState } from 'react'
import {
  IconFileText,
  IconStar,
  IconDownload,
  IconShieldCheck,
  IconSettings,
  IconBold,
  IconItalic,
  IconH2,
  IconList,
  IconQuote,
  IconCode,
  IconLink,
  IconTable,
} from '@tabler/icons-react'
import { PlaceholdersAndVanishInput } from '@/components/aceternity/placeholders-and-vanish-input'
import { CardSpotlight } from '@/components/aceternity/card-spotlight'
import { TextGenerateEffect } from '@/components/aceternity/text-generate-effect'
import { FloatingDock } from '@/components/aceternity/floating-dock'
import { PluginShell, PluginHeader, StatusBar, Chip, GhostButton, GoldButton } from '@/components/infinia/chrome'

const DOCS = [
  { name: '季度运营复盘.md', path: '工作区/运营', state: '已同步', active: true },
  { name: '蜂语发布清单.md', path: '工作区/发布', state: '草稿' },
  { name: '接入指南.md', path: 'docs/zh', state: '已同步' },
  { name: '会议纪要 · 9 月.md', path: '工作区/会议', state: '已同步' },
  { name: 'api-notes.md', path: '工作区/研发', state: '本地' },
]

const SOURCE = [
  '# 蜂语 4.1 季度运营复盘',
  '',
  '> 目标：把「一个语言」落到每一个面板。',
  '',
  '## 结论先行',
  '',
  '商店与应用已经共享同一套设计令牌，',
  '插件面板是这条路上最后一块拼图。',
  '',
  '- 暖白画布 + 白面板 + 发丝线',
  '- 金色只出现在可交互处',
  '- 蜂巢印记贯穿账户与插件',
  '',
  '## 数据',
  '',
  '| 指标 | 本季 | 环比 |',
  '| --- | --- | --- |',
  '| 周活跃 | 4,812 | +12.4% |',
  '| 商店转化 | 18.6% | +3.1 pts |',
]

const dockItems = [
  { title: '加粗', icon: <IconBold size={18} stroke={1.6} />, href: '#' },
  { title: '斜体', icon: <IconItalic size={18} stroke={1.6} />, href: '#' },
  { title: '二级标题', icon: <IconH2 size={18} stroke={1.6} />, href: '#' },
  { title: '列表', icon: <IconList size={18} stroke={1.6} />, href: '#' },
  { title: '引用', icon: <IconQuote size={18} stroke={1.6} />, href: '#' },
  { title: '行内代码', icon: <IconCode size={18} stroke={1.6} />, href: '#' },
  { title: '链接', icon: <IconLink size={18} stroke={1.6} />, href: '#' },
  { title: '表格', icon: <IconTable size={18} stroke={1.6} />, href: '#' },
]

export default function T1Markdown() {
  const [value, setValue] = useState('')
  return (
    <PluginShell
      expanded={false}
      brand={{ name: 'Markdown', icon: <IconFileText size={13} stroke={1.8} /> }}
      nav={[
        { label: '文档', icon: <IconFileText size={19} stroke={1.6} />, active: true },
        { label: '收藏', icon: <IconStar size={19} stroke={1.6} /> },
        { label: '导出', icon: <IconDownload size={19} stroke={1.6} /> },
        { label: '权限', icon: <IconShieldCheck size={19} stroke={1.6} /> },
        { label: '设置', icon: <IconSettings size={19} stroke={1.6} /> },
      ]}
    >

        {/* 文档栏：检索用官方 PlaceholdersAndVanishInput */}
        <aside className="flex w-[248px] shrink-0 flex-col border-r border-line bg-panel">
          <div className="flex h-13 items-center gap-2 border-b border-line px-4">
            <span className="text-[13px] font-semibold">文档</span>
            <span className="font-mono text-xs text-ink-3">5</span>
          </div>
          <div className="px-3 py-3">
            <PlaceholdersAndVanishInput
              placeholders={['搜索文档…', '按路径过滤…', '最近打开…']}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onSubmit={() => {}}
            />
          </div>
          <div className="flex-1 overflow-hidden px-2 pb-3">
            {DOCS.map((doc) => (
              <button
                key={doc.name}
                type="button"
                className={
                  'mb-0.5 flex w-full flex-col items-start gap-0.5 rounded-lg px-3 py-[7px] text-left transition-colors hover:bg-hover ' +
                  (doc.active ? 'infinia-active-pill' : '')
                }
              >
                <span className="truncate text-[13px]">{doc.name}</span>
                <span className="flex w-full items-center gap-2">
                  <span className="truncate font-mono text-[11px] text-ink-3">{doc.path}</span>
                  <span
                    className="ml-auto rounded-full px-1.5 py-px text-[10px]"
                    style={{
                      background: doc.state === '草稿' ? 'var(--c-tag)' : 'transparent',
                      color: doc.state === '草稿' ? 'var(--c-ink-2)' : 'var(--c-ink-3)',
                    }}
                  >
                    {doc.state}
                  </span>
                </span>
              </button>
            ))}
          </div>
        </aside>

        {/* 主区 */}
        <main className="relative flex min-w-0 flex-1 flex-col">
          <PluginHeader
            icon={<IconFileText size={14} stroke={1.8} />}
            name="Markdown 编辑器"
            category="text"
            version="v4.1.0"
            id="fan.summer.markdown"
            right={
              <>
                <Chip>已保存 · 刚刚</Chip>
                <GhostButton>切换布局</GhostButton>
                <GoldButton>
                  <IconDownload size={15} stroke={1.7} />
                  导出
                </GoldButton>
              </>
            }
          />

          {/* 编辑 / 预览 双栏 */}
          <div className="relative flex min-h-0 flex-1">
            <div className="flex min-w-0 flex-1 flex-col border-r border-line">
              <div className="editor-scroll flex-1 overflow-hidden px-5 py-5 font-mono text-[12.5px] leading-[1.75]">
                <div className="flex">
                  <div className="mr-4 select-none text-right text-ink-3">
                    {SOURCE.map((_, i) => (
                      <div key={i}>{i + 1}</div>
                    ))}
                  </div>
                  <div className="min-w-0 flex-1 whitespace-pre">
                    {SOURCE.map((line, i) => {
                      const isHead = line.startsWith('#')
                      const isQuote = line.startsWith('>')
                      const isList = line.startsWith('- ')
                      const isTable = line.startsWith('|')
                      return (
                        <div
                          key={i}
                          className={
                            isHead
                              ? 'font-semibold text-ink'
                              : isQuote
                                ? 'border-l-2 border-gold pl-3 text-ink-2'
                                : isList
                                  ? 'pl-3 text-ink'
                                  : isTable
                                    ? 'text-success'
                                    : 'text-ink-2'
                          }
                        >
                          {line || '\u00A0'}
                        </div>
                      )
                    })}
                  </div>
                </div>
              </div>
            </div>

            {/* 预览：官方 CardSpotlight（金色追光） */}
            <CardSpotlight
              radius={280}
              spotlightColor="rgba(234,176,75,0.18)"
              className="m-4 flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-line bg-panel"
            >
              <div className="flex h-10 items-center gap-2 border-b border-line px-5 font-mono text-[11px] text-ink-3">
                预览 · 实时渲染
                <span className="ml-auto">312 ms</span>
              </div>
              <article className="preview-scroll flex-1 overflow-hidden px-7 py-6">
                <TextGenerateEffect
                  words="蜂语 4.1 季度运营复盘"
                  className="text-left text-xl font-semibold tracking-tight text-ink"
                  duration={0.03}
                />
                <blockquote className="my-4 border-l-2 border-gold pl-3 text-[13px] text-ink-2">
                  目标：把「一个语言」落到每一个面板。
                </blockquote>
                <p className="mb-3 text-[13.5px] leading-relaxed text-ink-2">
                  商店与应用已经共享同一套设计令牌，插件面板是这条路上最后一块拼图。
                </p>
                <ul className="mb-4 space-y-1.5 text-[13.5px]">
                  <li className="flex gap-2">
                    <span className="mt-[7px] size-1 shrink-0 rounded-full bg-gold" />
                    暖白画布 + 白面板 + 发丝线
                  </li>
                  <li className="flex gap-2">
                    <span className="mt-[7px] size-1 shrink-0 rounded-full bg-gold" />
                    金色只出现在可交互处
                  </li>
                  <li className="flex gap-2">
                    <span className="mt-[7px] size-1 shrink-0 rounded-full bg-gold" />
                    蜂巢印记贯穿账户与插件
                  </li>
                </ul>
                <div className="overflow-hidden rounded-lg border border-line">
                  <div className="grid grid-cols-3 border-b border-line bg-muted-surface px-3 py-1.5 text-xs text-ink-2">
                    <span>指标</span>
                    <span>本季</span>
                    <span>环比</span>
                  </div>
                  {[
                    ['周活跃', '4,812', '+12.4%'],
                    ['商店转化', '18.6%', '+3.1 pts'],
                  ].map((row) => (
                    <div key={row[0]} className="grid grid-cols-3 px-3 py-1.5 text-xs">
                      <span className="text-ink">{row[0]}</span>
                      <span className="font-mono text-ink-2">{row[1]}</span>
                      <span className="font-mono text-success">{row[2]}</span>
                    </div>
                  ))}
                </div>
              </article>
            </CardSpotlight>
          </div>

          {/* 官方 FloatingDock：底部格式坞，跨双栏居中，悬停放大 */}
          <div className="pointer-events-none absolute inset-x-0 bottom-5 z-30 flex justify-center">
            <div className="pointer-events-auto">
              <FloatingDock items={dockItems} desktopClassName="bg-panel border border-line shadow-[0_8px_28px_rgba(24,24,27,0.10)]" />
            </div>
          </div>

          <StatusBar
            left={
              <>
                <span>工作进程 就绪</span>
                <span>渲染 312 ms</span>
                <span>字数 1,284</span>
              </>
            }
            right={<span>fan.summer.markdown · v4.1.0</span>}
          />
        </main>
    </PluginShell>
  )
}
