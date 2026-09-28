/* ================================================================
   T3 · 沟通中心类（category: network）— 代表插件：邮件中心
   骨架隐喻「中心工作台」：动效页签 + 收件列表 ⇄ 阅读-pane + 发送队列。
   Aceternity：Sidebar · Tabs(动效页签) · PlaceholdersAndVanishInput(检索)
   · CardSpotlight(阅读卡) · StatefulButton(发送三态) · MovingBorder(次级动效钮)
   · CardStack(发送队列卡片堆)
   ================================================================ */
import { useState } from 'react'
import {
  IconInbox,
  IconPencil,
  IconUsers,
  IconHistory,
  IconSettings,
  IconPaperclip,
  IconClockPlus,
  IconArchive,
  IconSend,
} from '@tabler/icons-react'
import { Tabs } from '@/components/aceternity/tabs'
import { PlaceholdersAndVanishInput } from '@/components/aceternity/placeholders-and-vanish-input'
import { CardSpotlight } from '@/components/aceternity/card-spotlight'
import { Button as StatefulButton } from '@/components/aceternity/stateful-button'
import { Button as MovingBorderButton } from '@/components/aceternity/moving-border'
import { CardStack } from '@/components/aceternity/card-stack'
import { PluginShell, PluginHeader, StatusBar, StatusChip, GhostButton, HexMark } from '@/components/infinia/chrome'

const MESSAGES = [
  {
    from: '林一舟',
    initial: '林',
    subject: '蜂语 4.1 发布检查单已通过',
    snippet: '冒烟、桌面 E2E、四插件回归全部绿灯，附检查单与产物清单……',
    time: '09:42',
    unread: true,
    attach: true,
    active: true,
  },
  {
    from: '陈曼',
    initial: '陈',
    subject: '商店视觉第三轮走查意见',
    snippet: '暖白与金的对比在低亮度屏上略吃力，建议金色交互件统一加深墨描边……',
    time: '08:15',
    unread: true,
  },
  {
    from: '自动化',
    initial: '夜',
    subject: 'nightly 构建报告 #482 成功',
    snippet: 'FengYu-4.1.0-SNAPSHOT shaded jar 128 MB，桌面三平台产物已归档……',
    time: '昨天',
    unread: true,
  },
  {
    from: '王砚舟',
    initial: '王',
    subject: 'X 银行 POC 反馈与排期',
    snippet: '客户希望编排平台侧增加统一审计视图，下周三前给出评估……',
    time: '昨天',
  },
  {
    from: '沈予',
    initial: '沈',
    subject: '插件市场类目运营周报',
    snippet: '本周新增 6 个第三方插件上架申请，text/file 类目占比最高……',
    time: '周一',
  },
]

export default function T3Email() {
  const [value, setValue] = useState('')
  return (
    <PluginShell
      expanded={false}
      brand={{ name: '邮件中心', icon: <IconInbox size={13} stroke={1.8} /> }}
      nav={[
        { label: '收件箱', icon: <IconInbox size={19} stroke={1.6} />, active: true },
        { label: '撰写', icon: <IconPencil size={19} stroke={1.6} /> },
        { label: '联系人', icon: <IconUsers size={19} stroke={1.6} /> },
        { label: '发送记录', icon: <IconHistory size={19} stroke={1.6} /> },
        { label: '设置', icon: <IconSettings size={19} stroke={1.6} /> },
      ]}
    >
      <main className="flex min-w-0 flex-1 flex-col">
        <PluginHeader
          icon={<IconInbox size={14} stroke={1.8} />}
          name="邮件中心"
          category="network"
          version="v4.1.0"
          id="fan.summer.email"
          right={
            <>
              <StatusChip>已连接 IMAP</StatusChip>
              <GhostButton>立即同步</GhostButton>
            </>
          }
        />

        {/* 官方 Tabs：动效页签，金色活动胶囊 */}
        <div className="border-b border-line bg-panel px-5 py-1.5">
          <Tabs
            tabs={[
              { title: '收集', value: 'collect', content: '' },
              { title: '撰写', value: 'compose', content: '' },
              { title: '批量', value: 'batch', content: '' },
              { title: '通讯录', value: 'contacts', content: '' },
              { title: '发送记录', value: 'records', content: '' },
            ]}
            containerClassName="!gap-1"
            activeTabClassName="bg-gold text-gold-ink shadow-none"
            tabClassName="text-[12.5px] px-3 py-1.5 !text-ink-2"
            contentClassName="hidden"
          />
        </div>

        <div className="flex min-h-0 flex-1 gap-4 px-5 py-4">
          {/* 收件列表 */}
          <aside className="flex w-[330px] shrink-0 flex-col rounded-xl border border-line bg-panel">
            <div className="border-b border-line p-3">
              <PlaceholdersAndVanishInput
                placeholders={['搜索发件人…', '搜索主题…', '含附件…']}
                value={value}
                onChange={(e) => setValue(e.target.value)}
                onSubmit={() => {}}
              />
            </div>
            <div className="min-h-0 flex-1 overflow-hidden">
              {MESSAGES.map((m) => (
                <button
                  key={m.subject}
                  type="button"
                  className={
                    'flex w-full flex-col gap-1 border-b border-line px-4 py-3 text-left transition-colors last:border-b-0 hover:bg-hover ' +
                    (m.active ? 'infinia-active-pill !rounded-none' : '')
                  }
                >
                  <span className="flex items-center gap-2">
                    <HexMark size={22} className="!bg-[var(--c-tag)] !text-[var(--c-ink-2)]">
                      <span className="text-[11px] font-semibold">{m.initial}</span>
                    </HexMark>
                    <span className="text-[13px] font-medium">{m.from}</span>
                    {m.attach && <IconPaperclip size={13} stroke={1.6} className="text-ink-3" />}
                    {m.unread && <span className="ml-auto inline-block size-2 shrink-0 rounded-full bg-gold" />}
                    <span className={'font-mono text-[11px] ' + (m.unread ? 'text-ink-3' : 'ml-auto text-ink-3')}>
                      {m.time}
                    </span>
                  </span>
                  <span className="truncate text-[13px] text-ink">{m.subject}</span>
                  <span className="truncate text-xs text-ink-2">{m.snippet}</span>
                </button>
              ))}
            </div>
          </aside>

          {/* 阅读 pane：官方 CardSpotlight */}
          <CardSpotlight
            radius={320}
            spotlightColor="rgba(234,176,75,0.15)"
            className="flex min-w-0 flex-1 flex-col rounded-xl border border-line bg-panel"
          >
            <div className="border-b border-line px-6 py-4">
              <h2 className="text-[15px] font-semibold tracking-tight">蜂语 4.1 发布检查单已通过</h2>
              <div className="mt-1.5 flex items-center gap-3 text-xs text-ink-2">
                <span>林一舟 · 运维平台</span>
                <span className="font-mono text-ink-3">今天 09:42</span>
              </div>
            </div>
            <div className="flex-1 space-y-3.5 overflow-hidden px-6 py-5 text-[13.5px] leading-relaxed text-ink-2">
              <p>各位，发布检查单已全部通过：</p>
              <p>
                冒烟脚本覆盖 32 个端点全部 200；桌面 E2E 在 macOS 与 Linux 双平台稳定通过；
                四个官方插件（markdown、excel、email、offlinepython）回归无新增问题。
              </p>
              <p>
                产物清单见附件。若无异议，今晚按既定窗口推进商店上架，明早同步公告。
              </p>
              <p className="text-ink-3">一舟</p>
            </div>
            <div className="flex items-center gap-3 border-t border-line px-6 py-3.5">
              <StatefulButton
                className="h-8 rounded-lg bg-gold px-4 text-[13px] font-medium text-gold-ink hover:bg-gold-hover [&_svg]:!text-gold-ink [&_.loader]:border-gold-ink/40 [&_.check]:border-gold-ink"
              >
                <IconSend size={14} stroke={1.7} className="mr-1" />
                发送回复
              </StatefulButton>
              <MovingBorderButton
                borderRadius="8px"
                duration={3600}
                containerClassName="h-8 w-auto bg-panel text-[13px] text-ink"
                borderClassName="bg-[linear-gradient(90deg,rgba(234,176,75,0.0),rgba(234,176,75,0.65),rgba(234,176,75,0.0))]"
                className="bg-panel px-4 text-[13px] text-ink"
              >
                <span className="flex items-center gap-1.5">
                  <IconClockPlus size={14} stroke={1.7} />
                  定时跟进
                </span>
              </MovingBorderButton>
              <GhostButton className="ml-auto">
                <IconArchive size={14} stroke={1.6} />
                归档
              </GhostButton>
            </div>
          </CardSpotlight>

          {/* 右栏：官方 CardStack 发送队列 */}
          <aside className="flex w-[250px] shrink-0 flex-col gap-4">
            <div className="flex min-h-0 flex-1 flex-col rounded-xl border border-line bg-panel p-4">
              <div className="mb-4 flex items-center justify-between">
                <span className="text-[13px] font-semibold">发送队列</span>
                <span className="font-mono text-[11px] text-ink-3">3 批</span>
              </div>
              <div className="relative h-[300px]">
                <CardStack
                  offset={9}
                  scaleFactor={0.05}
                  items={[
                    {
                      id: 1,
                      name: '华东客户 · 月度账单',
                      designation: '128 位收件人 · 96% 已送达',
                      content: <QueueBody status="发送中" tone="warning" progress={78} />,
                    },
                    {
                      id: 2,
                      name: '发布会邀请函',
                      designation: '62 位收件人 · 已送达',
                      content: <QueueBody status="已完成" tone="success" />,
                    },
                    {
                      id: 3,
                      name: '安全公告 · 补丁说明',
                      designation: '全员 · 排队中',
                      content: <QueueBody status="排队中" tone="idle" />,
                    },
                  ]}
                />
              </div>
            </div>
            <div className="rounded-xl border border-line bg-panel px-4 py-3.5">
              <div className="text-xs text-ink-2">今日发出</div>
              <div className="mt-1 font-mono text-xl font-semibold tracking-tight">1,204</div>
              <div className="mt-0.5 font-mono text-[11px] text-success">退信率 0.4%</div>
            </div>
          </aside>
        </div>

        <StatusBar
          left={
            <>
              <span>IMAP 已连接</span>
              <span>未读 3</span>
              <span>队列 1 批运行中</span>
            </>
          }
          right={<span>fan.summer.email · v4.1.0</span>}
        />
      </main>
    </PluginShell>
  )
}

function QueueBody({ status, tone, progress }: { status: string; tone: 'warning' | 'success' | 'idle'; progress?: number }) {
  const color =
    tone === 'warning' ? 'var(--c-warning)' : tone === 'success' ? 'var(--c-success)' : 'var(--c-ink-3)'
  const bg =
    tone === 'warning' ? 'var(--c-warning-bg)' : tone === 'success' ? 'var(--c-success-bg)' : 'var(--c-tag)'
  return (
    <div className="mt-4">
      <span className="rounded-full px-2 py-0.5 text-[11px]" style={{ background: bg, color }}>
        {status}
      </span>
      {tone === 'warning' && (
        <span className="mt-3 block h-1 overflow-hidden rounded-full" style={{ background: 'rgba(24,24,27,0.08)' }}>
          <span className="block h-full rounded-full" style={{ width: `${progress}%`, background: 'var(--c-warning)' }} />
        </span>
      )}
    </div>
  )
}
