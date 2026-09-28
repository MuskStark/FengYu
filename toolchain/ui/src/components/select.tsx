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
    const onPointer = (event: MouseEvent) => {
      if (!(event.target instanceof HTMLElement) || !event.target.closest('[data-infinia-menu]')) onDismiss()
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
  onPick,
}: {
  option: SelectOption
  selected: boolean
  highlighted: boolean
  size: SelectSize
  onPick: (value: string) => void
}) {
  return (
    <button
      type="button"
      role="option"
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
  const { cursor, setCursor, move } = useMenuCursor(options, open)
  useDismiss(() => setOpen(false), open)

  const pick = (next: string) => {
    onChange(next)
    setOpen(false)
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
    <div data-select="" className={cn('relative', rest.disabled && 'opacity-50', className)}>
      <button
        type="button"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        {...rest}
        data-state={open ? 'open' : 'closed'}
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
        <MenuSurface>
          {options.map((option, index) => (
            <MenuOption
              key={option.value}
              option={option}
              size={size}
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
  const { cursor, setCursor, move } = useMenuCursor(options, open)
  useDismiss(() => { setOpen(false); setQuery(null) }, open)

  const needle = (query ?? '').trim().toLowerCase()
  const filtered = needle
    ? options.filter((option) => String(option.value).toLowerCase().includes(needle) || String(option.label).toLowerCase().includes(needle))
    : options

  const pick = (next: string) => {
    onCommit(next)
    setQuery(null)
    setOpen(false)
  }

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      move(event.key === 'ArrowDown' ? 1 : -1)
    } else if (event.key === 'Enter' && open && cursor >= 0 && filtered[cursor]) {
      event.preventDefault()
      pick(filtered[cursor].value)
    }
  }

  const listId = useId()
  return (
    <div data-combobox="" className={cn('relative', rest.disabled && 'opacity-50', className)}>
      <div className="relative">
        <input
          role="combobox"
          {...rest}
          value={query ?? value}
          placeholder={placeholder !== undefined ? String(placeholder) : undefined}
          onChange={(event) => {
            setQuery(event.target.value)
            setOpen(true)
            setCursor(0)
            // datalist 语义：自由输入即时提交（不强制来自列表）。
            onCommit(event.target.value)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          className={cn(
            'w-full rounded-lg border border-line bg-panel pr-8 text-ink transition-colors outline-none',
            SIZES[size],
            'placeholder:text-ink-3 hover:border-line-strong focus-visible:border-[var(--c-input-border-focused)]',
          )}
        />
        <IconSelector size={14} stroke={1.8} className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-ink-3" />
      </div>
      {open && filtered.length > 0 ? (
        <MenuSurface id={listId}>
          {filtered.map((option, index) => (
            <MenuOption
              key={option.value}
              option={option}
              size={size}
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
