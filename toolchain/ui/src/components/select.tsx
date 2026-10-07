/* ================================================================
   Infinia Select / Combobox — 替代原生 <select>/<datalist>。
   原生弹层（系统样式、方角、无法换肤）破坏 Infinia 语言；这里是
   自绘弹层：发丝线圆角卡（raised 底 + 投影）、选项 hover/选中态、
   金色对勾、完整键盘循环（↑↓ Enter Esc）。Combobox 允许自由输入
   （等价 datalist 语义：可键入不在列表内的值）。
   ================================================================ */
import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { IconCheck, IconChevronDown, IconSelector } from '@tabler/icons-react'
import { cn } from '../lib/utils'

export interface SelectOption {
  value: string
  label: ReactNode
  disabled?: boolean
}

export type SelectSize = 'sm' | 'md'

const SIZES: Record<SelectSize, string> = {
  sm: 'h-7 px-2 text-xs',
  md: 'h-9 px-3 text-[13px]',
}

function useDismiss(onDismiss: () => void, active: boolean) {
  useEffect(() => {
    if (!active) return
    // 弹层与锚定字段（触发器/输入框）是一个整体：点它们不算"外部"。
    const onPointer = (event: MouseEvent) => {
      if (!(event.target instanceof HTMLElement) || !event.target.closest('[data-infinia-menu], [data-infinia-anchor]')) onDismiss()
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onDismiss()
    }
    document.addEventListener('mousedown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [active, onDismiss])
}

function MenuSurface({ children, className, id }: { children: ReactNode; className?: string; id?: string }) {
  return (
    <div
      id={id}
      data-infinia-menu=""
      role="listbox"
      className={cn(
        'absolute left-0 right-0 top-full z-30 mt-1 max-h-64 overflow-y-auto rounded-xl border border-line bg-raised p-1',
        'shadow-[0_12px_28px_rgba(24,24,27,0.14)]',
        className,
      )}
    >
      {children}
    </div>
  )
}

function MenuOption({
  option,
  selected,
  highlighted,
  size,
  id,
  onPick,
}: {
  option: SelectOption
  selected: boolean
  highlighted: boolean
  size: SelectSize
  id?: string
  onPick: (value: string) => void
}) {
  return (
    <button
      type="button"
      role="option"
      id={id}
      aria-selected={selected}
      disabled={option.disabled}
      data-option={option.value}
      onClick={() => onPick(option.value)}
      className={cn(
        'flex w-full items-center justify-between gap-2 rounded-lg px-2.5 text-left transition-colors',
        size === 'sm' ? 'h-7 text-xs' : 'h-8 text-[13px]',
        highlighted && 'bg-hover',
        selected ? 'font-medium text-ink' : 'text-ink-2',
        option.disabled && 'pointer-events-none opacity-40',
      )}
    >
      <span className="min-w-0 truncate">{option.label}</span>
      {selected ? <IconCheck size={14} stroke={2} className="shrink-0 text-gold" /> : null}
    </button>
  )
}

/** 键盘循环的共享游标逻辑（↑↓ 移动、跳过禁用项）。 */
function useMenuCursor(options: SelectOption[], open: boolean) {
  const [cursor, setCursor] = useState(-1)
  useEffect(() => {
    if (!open) setCursor(-1)
  }, [open])
  const enabledIndices = useMemo(
    () => options.map((option, index) => (option.disabled ? -1 : index)).filter((index) => index >= 0),
    [options],
  )
  const move = (delta: number) => {
    if (enabledIndices.length === 0) return
    const current = enabledIndices.indexOf(cursor)
    const next = enabledIndices[(current + delta + enabledIndices.length) % enabledIndices.length]
    setCursor(next)
  }
  return { cursor, setCursor, move }
}

/** Infinia 下拉选择（受控）。等价 <select> 但弹层与语言一致。 */
export function Select({
  value,
  options,
  onChange,
  placeholder = '请选择',
  size = 'md',
  className,
  ...rest
}: {
  value: string
  options: SelectOption[]
  onChange: (value: string) => void
  placeholder?: ReactNode
  size?: SelectSize
  disabled?: boolean
  className?: string
} & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'onChange' | 'value' | 'className'>) {
  const [open, setOpen] = useState(false)
  const selected = options.find((option) => option.value === value) ?? null
  const listId = useId()
  const triggerRef = useRef<HTMLButtonElement>(null)
  const optionId = (index: number) => `${listId}-option-${index}`
  const { cursor, setCursor, move } = useMenuCursor(options, open)
  // 关闭即归还焦点给触发器（Esc / 点选 / 点击外部统一走这里）。
  const close = () => {
    setOpen(false)
    triggerRef.current?.focus()
  }
  useDismiss(close, open)

  const pick = (next: string) => {
    onChange(next)
    close()
  }

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (!open) {
        setOpen(true)
        // 键盘首开：光标落在当前项；无选中则从头开始。
        setCursor(Math.max(0, options.findIndex((option) => option.value === value)))
      } else move(event.key === 'ArrowDown' ? 1 : -1)
    } else if (event.key === 'Enter' || event.key === ' ') {
      if (open) {
        event.preventDefault()
        if (cursor >= 0 && !options[cursor]?.disabled) pick(options[cursor].value)
      }
    }
  }

  return (
    // data-infinia-anchor 圈出"触发器 + 弹层"整体：点击触发器只做开合切换，不算外部关闭。
    <div data-select="" data-infinia-anchor="" className={cn('relative', rest.disabled && 'opacity-50', className)}>
      <button
        {...rest}
        type="button"
        ref={triggerRef}
        data-state={open ? 'open' : 'closed'}
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open && cursor >= 0 ? optionId(cursor) : undefined}
        onClick={() => setOpen((previous) => !previous)}
        onKeyDown={onKeyDown}
        className={cn(
          'flex w-full items-center justify-between gap-2 rounded-lg border border-line bg-panel text-left transition-colors',
          SIZES[size],
          'hover:border-line-strong focus-visible:border-[var(--c-input-border-focused)]',
          open && 'border-[var(--c-input-border-focused)]',
        )}
      >
        <span className={cn('min-w-0 truncate', selected ? 'text-ink' : 'text-ink-3')}>{selected ? selected.label : placeholder}</span>
        <IconChevronDown size={14} stroke={1.8} className={cn('shrink-0 text-ink-3 transition-transform', open && 'rotate-180')} />
      </button>
      {open ? (
        <MenuSurface id={listId}>
          {options.map((option, index) => (
            <MenuOption
              key={option.value}
              option={option}
              size={size}
              id={optionId(index)}
              selected={option.value === value}
              highlighted={index === cursor}
              onPick={pick}
            />
          ))}
        </MenuSurface>
      ) : null}
    </div>
  )
}

/**
 * Infinia 组合框：可自由输入 + 建议弹层（datalist 的替代）。
 * 输入即时过滤并 onCommit；点选建议即填入。
 */
export function Combobox({
  value,
  options,
  onCommit,
  placeholder,
  size = 'md',
  className,
  ...rest
}: {
  value: string
  options: SelectOption[]
  /** 自由输入与点选统一走这里（保持 datalist 的自由文本语义）。 */
  onCommit: (value: string) => void
  placeholder?: ReactNode
  size?: SelectSize
  disabled?: boolean
  className?: string
  id?: string
  'data-field'?: string
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState<string | null>(null)
  const listId = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const optionId = (index: number) => `${listId}-option-${index}`

  const needle = (query ?? '').trim().toLowerCase()
  const filtered = needle
    ? options.filter((option) => String(option.value).toLowerCase().includes(needle) || String(option.label).toLowerCase().includes(needle))
    : options

  // 游标基于过滤后的列表：键入过滤后 ↑↓/Enter 仍指向弹层里真实的选项。
  const { cursor, setCursor, move } = useMenuCursor(filtered, open)
  // 归还焦点时的"刚关闭"守卫：close() 会 focus 输入框，而输入框 onFocus 会展开弹层——
  // 不抑制的话 Esc/点选关掉的菜单会被自己立刻重新打开（jsdom 的 focus() 无条件派发
  // 事件，真实浏览器里也是同 tick 竞态）。下一个宏任务清除，用户真正的再次聚焦不受影响。
  const suppressOpenOnFocusRef = useRef(false)
  const close = () => {
    setOpen(false)
    setQuery(null)
    suppressOpenOnFocusRef.current = true
    inputRef.current?.focus()
    setTimeout(() => { suppressOpenOnFocusRef.current = false }, 0)
  }
  useDismiss(close, open)

  const pick = (next: string) => {
    onCommit(next)
    setQuery(null)
    setOpen(false)
    inputRef.current?.focus()
  }

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      move(event.key === 'ArrowDown' ? 1 : -1)
    } else if (event.key === 'Enter' && open && cursor >= 0) {
      const option = filtered[cursor]
      // 与 Select 一致：禁用项不可被 Enter 选中（也不吞掉 Enter 的默认行为）。
      if (option && !option.disabled) {
        event.preventDefault()
        pick(option.value)
      }
    }
  }

  const menuOpen = open && filtered.length > 0
  return (
    // data-infinia-anchor 圈出"输入框 + 弹层"整体：点击输入框不算外部关闭（focus 本就保持展开）。
    <div data-combobox="" data-infinia-anchor="" className={cn('relative', rest.disabled && 'opacity-50', className)}>
      <div className="relative">
        <input
          {...rest}
          ref={inputRef}
          role="combobox"
          value={query ?? value}
          placeholder={placeholder !== undefined ? String(placeholder) : undefined}
          aria-expanded={menuOpen}
          aria-autocomplete="list"
          aria-controls={menuOpen ? listId : undefined}
          aria-activedescendant={menuOpen && cursor >= 0 ? optionId(cursor) : undefined}
          onChange={(event) => {
            setQuery(event.target.value)
            setOpen(true)
            setCursor(0)
            // datalist 语义：自由输入即时提交（不强制来自列表）。
            onCommit(event.target.value)
          }}
          onFocus={() => {
            if (suppressOpenOnFocusRef.current) return
            setOpen(true)
          }}
          onKeyDown={onKeyDown}
          className={cn(
            'w-full rounded-lg border border-line bg-panel pr-8 text-ink transition-colors outline-none',
            SIZES[size],
            'placeholder:text-ink-3 hover:border-line-strong focus-visible:border-[var(--c-input-border-focused)]',
          )}
        />
        <IconSelector size={14} stroke={1.8} className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-ink-3" />
      </div>
      {menuOpen ? (
        <MenuSurface id={listId}>
          {filtered.map((option, index) => (
            <MenuOption
              key={option.value}
              option={option}
              size={size}
              id={optionId(index)}
              selected={option.value === value}
              highlighted={index === cursor}
              onPick={pick}
            />
          ))}
        </MenuSurface>
      ) : null}
    </div>
  )
}
