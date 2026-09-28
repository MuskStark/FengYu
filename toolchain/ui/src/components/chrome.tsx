/* ================================================================
   Infinia 插件模板外壳 — 聚焦工作台版（无侧边栏）。
   设计令牌见 ../styles/plugin-ui.css（4.1.0 Infinia store 设计语言）：
   暖白画布 / 白面板 / 发丝线 / 金 #eab04b 唯一高饱和交互色。
   壳 = 全幅内容 + 顶部聚焦条（视图胶囊 + 动作）+ 底部状态条；
   返回与插件身份由宿主面板顶栏负责，这里不重复。
   ================================================================ */
import { useState, type ReactNode } from 'react'
import { cn } from '../lib/utils'

/** 六边形插件印记（蜂巢护照）：金底 + 深墨图标。 */
export function HexMark({
  children,
  size = 26,
  className,
}: {
  children?: ReactNode
  size?: number
  className?: string
}) {
  return (
    <span
      className={cn('infinia-hex inline-grid shrink-0 place-items-center', className)}
      style={{
        width: size,
        height: Math.round(size * 1.12),
        background: 'var(--c-gold)',
        color: 'var(--c-gold-ink)',
      }}
    >
      {children}
    </span>
  )
}

/** 中性标签胶囊（tag 底），用于分类/版本/状态等次级信息。 */
export function Chip({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full bg-tag px-2.5 py-0.5 text-xs text-ink-2',
        className,
      )}
    >
      {children}
    </span>
  )
}

export type StatusTone = 'success' | 'warning' | 'danger' | 'idle'

/** 语义状态胶囊：小圆点表达真实状态（语义点，非装饰）。 */
export function StatusChip({ tone = 'success', children }: { tone?: StatusTone; children: ReactNode }) {
  const color =
    tone === 'success'
      ? 'var(--c-success)'
      : tone === 'warning'
        ? 'var(--c-warning)'
        : tone === 'danger'
          ? 'var(--c-danger)'
          : 'var(--c-ink-3)'
  return (
    <Chip>
      <span className="inline-block size-1.5 rounded-full" style={{ background: color }} />
      {children}
    </Chip>
  )
}

/** 主按钮：金底深墨字（Infinia 规则：金填永远配深墨）。 */
export function GoldButton({
  children,
  className,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { children: ReactNode }) {
  return (
    <button
      type="button"
      className={cn(
        'inline-flex h-8 items-center gap-1.5 rounded-lg bg-gold px-3.5 text-[13px] font-medium text-gold-ink',
        'transition-transform active:translate-y-px hover:bg-gold-hover disabled:pointer-events-none disabled:opacity-50',
        className,
      )}
      {...props}
    >
      {children}
    </button>
  )
}

/** 次按钮：面板底 + 发丝线。 */
export function GhostButton({
  children,
  className,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { children: ReactNode }) {
  return (
    <button
      type="button"
      className={cn(
        'inline-flex h-8 items-center gap-1.5 rounded-lg border border-line bg-panel px-3.5 text-[13px] text-ink',
        'transition-colors hover:border-line-strong hover:bg-muted-surface active:translate-y-px disabled:pointer-events-none disabled:opacity-50',
        className,
      )}
      {...props}
    >
      {children}
    </button>
  )
}

/** 内容区页头：六边形印记 + 插件名 + 分类/版本 + 右侧动作。 */
export function PluginHeader({
  icon,
  name,
  category,
  version,
  right,
  className,
}: {
  icon?: ReactNode
  name: ReactNode
  category?: ReactNode
  version?: ReactNode
  right?: ReactNode
  className?: string
}) {
  return (
    <header
      data-plugin-header=""
      className={cn('flex h-[52px] shrink-0 items-center gap-3 border-b border-line bg-panel px-5', className)}
    >
      {icon ? <HexMark size={24}>{icon}</HexMark> : null}
      <h1 className="truncate text-[15px] font-semibold tracking-tight">{name}</h1>
      {category ? <Chip>{category}</Chip> : null}
      {version ? <span className="font-mono text-xs text-ink-3">{version}</span> : null}
      {right ? <div className="ml-auto flex items-center gap-2">{right}</div> : null}
    </header>
  )
}

/** 底部状态条：发丝线上沿 + mono 小字，与主程序 fx-statusbar 同构。 */
export function StatusBar({ left, right }: { left?: ReactNode; right?: ReactNode }) {
  return (
    <footer
      data-status-bar=""
      className="flex h-7 shrink-0 items-center gap-4 border-t border-line bg-muted-surface px-4 font-mono text-[11px] text-ink-2"
    >
      <div className="flex items-center gap-4">{left}</div>
      {right ? <div className="ml-auto flex items-center gap-4">{right}</div> : null}
    </footer>
  )
}

/**
 * 聚焦工作台壳（3.1 设计：无侧边栏）。宿主面板已提供返回与插件身份，
 * 插件自身只保留任务级 chrome：全幅内容区 + 可选顶部聚焦条 + 可选状态条。
 *
 * 视图切换不再用侧边栏——多视图插件把视图放进 {@link PluginBar} 的
 * Tabs 胶囊（官方 Aceternity Tabs 换肤），单用途插件直接省略。
 */
export function PluginShell({
  children,
  className,
}: {
  children: ReactNode
  className?: string
}) {
  return (
    <div
      data-plugin-shell=""
      className={cn('flex h-dvh w-full flex-col overflow-hidden bg-canvas text-ink', className)}
    >
      {children}
    </div>
  )
}

export interface PluginBarTab {
  value: string
  title: string
  icon?: ReactNode
}

/**
 * 顶部聚焦条：一屏之内的插件级 chrome。左侧可携带面包屑/标题（可选），
 * 中间是视图切换（官方 Aceternity Tabs 换肤：白色胶囊 + 金色活动丸，
 * layoutId 弹簧滑动），右侧是上下文动作与状态。宿主顶栏负责返回与身份，
 * 这里不再重复。
 */
export function PluginBar({
  tabs,
  active,
  defaultActive,
  onNavigate,
  title,
  right,
  className,
}: {
  /** 视图集；省略即纯动作条。 */
  tabs?: PluginBarTab[]
  active?: string
  defaultActive?: string
  onNavigate?: (value: string) => void
  /** 条内标题/面包屑（可选）。 */
  title?: ReactNode
  /** 右侧动作/状态槽。 */
  right?: ReactNode
  className?: string
}) {
  const [internalActive, setInternalActive] = useState(defaultActive ?? tabs?.[0]?.value ?? '')
  const currentActive = active ?? internalActive
  const handleNavigate = (value: string) => {
    setInternalActive(value)
    onNavigate?.(value)
  }
  return (
    <div
      data-plugin-bar=""
      className={cn(
        'flex h-12 shrink-0 items-center gap-3 border-b border-line bg-panel px-4',
        className,
      )}
    >
      {title ? <div className="mr-1 min-w-0 shrink-0 text-[13px] font-medium text-ink-2">{title}</div> : null}
      {tabs && tabs.length > 0 ? (
        <nav data-plugin-tabs="" aria-label="插件视图" className="flex min-w-0 items-center gap-0.5">
          {tabs.map((tab) => {
            const isActive = tab.value === currentActive
            return (
              <button
                key={tab.value}
                type="button"
                data-tab={tab.value}
                aria-current={isActive ? 'page' : undefined}
                onClick={() => handleNavigate(tab.value)}
                className={cn(
                  'relative inline-flex h-8 items-center gap-1.5 rounded-full px-3 text-[13px] transition-colors',
                  isActive
                    ? 'bg-gold font-medium text-gold-ink'
                    : 'text-ink-2 hover:bg-hover hover:text-ink',
                )}
              >
                {tab.icon}
                <span className="truncate">{tab.title}</span>
              </button>
            )
          })}
        </nav>
      ) : null}
      {right ? <div className="ml-auto flex shrink-0 items-center gap-2">{right}</div> : null}
    </div>
  )
}
