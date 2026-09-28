/* ================================================================
   Infinia 插件模板外壳 — 四类差异化模板共用的骨架件。
   设计令牌来自 zai.css（4.1.0 Infinia store 设计语言）：
   暖白画布 / 白面板 / #e5e2db 发丝线 / 金 #eab04b 唯一高饱和交互色。
   导航壳 = 官方 Aceternity Sidebar 换肤；选中态 = 中性胶囊 + 2px
   金色内嵌条（zai 签名规则，绝不只靠字重）。
   ================================================================ */
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import {
  Sidebar,
  SidebarBody,
  SidebarLink,
} from '@/components/aceternity/sidebar'

export interface PluginNav {
  label: string
  icon: ReactNode
  active?: boolean
}

/** 六边形插件印记（蜂巢护照）：金底 + 深墨图标。 */
export function HexMark({
  children,
  size = 26,
  className,
}: {
  children: ReactNode
  size?: number
  className?: string
}) {
  return (
    <span
      className={cn('infinia-hex inline-grid place-items-center shrink-0', className)}
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
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs whitespace-nowrap',
        'bg-tag text-ink-2',
        className,
      )}
    >
      {children}
    </span>
  )
}

/** 语义状态胶囊：小圆点表达真实状态（语义点，非装饰）。 */
export function StatusChip({
  tone = 'success',
  children,
}: {
  tone?: 'success' | 'warning' | 'danger' | 'idle'
  children: ReactNode
}) {
  const color =
    tone === 'success' ? 'var(--c-success)' : tone === 'warning' ? 'var(--c-warning)' : tone === 'danger' ? 'var(--c-danger)' : 'var(--c-ink-3)'
  return (
    <Chip>
      <span className="inline-block size-1.5 rounded-full" style={{ background: color }} />
      {children}
    </Chip>
  )
}

/** 主按钮：金底深墨字（Infinia 规则：金填永远配深墨）。 */
export function GoldButton({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <button
      type="button"
      className={cn(
        'inline-flex h-8 items-center gap-1.5 rounded-lg px-3.5 text-[13px] font-medium',
        'bg-gold text-gold-ink transition-transform active:translate-y-px hover:bg-gold-hover',
        className,
      )}
    >
      {children}
    </button>
  )
}

/** 次按钮：面板底 + 发丝线。 */
export function GhostButton({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <button
      type="button"
      className={cn(
        'inline-flex h-8 items-center gap-1.5 rounded-lg border border-line bg-panel px-3.5 text-[13px] text-ink',
        'transition-colors hover:border-line-strong hover:bg-muted-surface active:translate-y-px',
        className,
      )}
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
  id,
  right,
}: {
  icon: ReactNode
  name: string
  category: string
  version: string
  id: string
  right?: ReactNode
}) {
  return (
    <header className="flex h-13 shrink-0 items-center gap-3 border-b border-line bg-panel px-5">
      <HexMark size={24}>{icon}</HexMark>
      <h1 className="text-[15px] font-semibold tracking-tight">{name}</h1>
      <Chip>{category}</Chip>
      <span className="font-mono text-xs text-ink-3">{version}</span>
      <div className="ml-auto flex items-center gap-2">{right}</div>
    </header>
  )
}

/** 底部状态条：发丝线上沿 + mono 小字，与主程序 fx-statusbar 同构。 */
export function StatusBar({ left, right }: { left: ReactNode; right?: ReactNode }) {
  return (
    <footer className="flex h-7 shrink-0 items-center gap-4 border-t border-line bg-muted-surface px-4 font-mono text-[11px] text-ink-2">
      <div className="flex items-center gap-4">{left}</div>
      <div className="ml-auto flex items-center gap-4">{right}</div>
    </footer>
  )
}

/**
 * 插件导航壳：官方 Aceternity Sidebar 的 Infinia 换肤。
 * hover 展开（60px 轨道 ⇄ 300px 全宽），移动端走官方 MobileSidebar。
 */
export function PluginShell({
  nav,
  expanded = false,
  brand,
  children,
}: {
  nav: PluginNav[]
  expanded?: boolean
  brand: { name: string; icon: ReactNode }
  children: ReactNode
}) {
  return (
    <Sidebar open={expanded} setOpen={() => {}}>
      <div className="flex h-screen w-full overflow-hidden bg-canvas text-ink">
        <SidebarBody className="!w-auto gap-2 border-r border-line bg-muted-surface !px-2.5 !py-3">
          <div className="mb-2 flex items-center gap-2 px-1">
            <HexMark size={22}>{brand.icon}</HexMark>
          </div>
          {nav.map((item) => (
            <SidebarLink
              key={item.label}
              link={{ label: item.label, href: '#', icon: item.icon }}
              className={cn(
                'rounded-lg !py-2 text-ink-2 transition-colors hover:bg-hover hover:text-ink',
                item.active && 'infinia-active-pill !text-ink',
              )}
            />
          ))}
        </SidebarBody>
        {children}
      </div>
    </Sidebar>
  )
}
