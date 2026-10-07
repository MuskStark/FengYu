import { describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Select, Combobox } from '../src/components/select'

const FRUITS = [
  { value: 'apple', label: '苹果' },
  { value: 'banana', label: '香蕉' },
  { value: 'cherry', label: '樱桃' },
]

describe('Select', () => {
  it('renders the selected label and opens an Infinia menu on click', () => {
    render(<Select value="banana" options={FRUITS} onChange={() => {}} />)
    const trigger = screen.getByRole('combobox')
    expect(trigger.textContent).toContain('香蕉')
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(trigger)
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    expect(document.querySelector('[data-infinia-menu]')).not.toBeNull()
    expect(document.querySelectorAll('[data-infinia-menu] [role="option"]').length).toBe(3)
  })

  it('marks the selected option with the gold check and emits onChange on pick', () => {
    const onChange = vi.fn()
    render(<Select value="apple" options={FRUITS} onChange={onChange} />)
    fireEvent.click(screen.getByRole('combobox'))
    fireEvent.click(document.querySelector('[data-option="cherry"]') as HTMLElement)
    expect(onChange).toHaveBeenCalledWith('cherry')
  })

  it('cycles options with ArrowDown and selects with Enter', () => {
    const onChange = vi.fn()
    render(<Select value="" options={FRUITS} onChange={onChange} placeholder="选一个" />)
    const trigger = screen.getByRole('combobox')
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('banana')
  })

  it('closes on Escape', () => {
    render(<Select value="apple" options={FRUITS} onChange={() => {}} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    expect(document.querySelector('[data-infinia-menu]')).not.toBeNull()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(document.querySelector('[data-infinia-menu]')).toBeNull()
  })

  it('wires the trigger to the listbox (haspopup/controls/activedescendant/selected)', () => {
    render(<Select value="banana" options={FRUITS} onChange={() => {}} />)
    const trigger = screen.getByRole('combobox')
    expect(trigger.getAttribute('aria-haspopup')).toBe('listbox')
    fireEvent.click(trigger)
    const listbox = document.querySelector('[data-infinia-menu][role="listbox"]') as HTMLElement
    expect(listbox).not.toBeNull()
    expect(trigger.getAttribute('aria-controls')).toBe(listbox.id)
    // Keyboard move drives aria-activedescendant onto the highlighted option.
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    const activeId = trigger.getAttribute('aria-activedescendant')
    expect(activeId).toBeTruthy()
    expect(document.getElementById(activeId ?? '')?.getAttribute('data-option')).toBe('apple')
    expect(document.querySelector('[data-option="banana"]')!.getAttribute('aria-selected')).toBe('true')
  })

  it('returns focus to the trigger on Escape, outside-click, and pick', () => {
    const onChange = vi.fn()
    render(<Select value="apple" options={FRUITS} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    // Escape
    fireEvent.click(trigger)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(document.querySelector('[data-infinia-menu]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    // Outside click
    fireEvent.click(trigger)
    fireEvent.mouseDown(document.body)
    expect(document.querySelector('[data-infinia-menu]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    // Pick from the menu (blur first so the focus() return is observable in
    // jsdom, where clicks don't move focus)
    fireEvent.click(trigger)
    fireEvent.blur(trigger)
    fireEvent.click(document.querySelector('[data-option="cherry"]') as HTMLElement)
    expect(onChange).toHaveBeenCalledWith('cherry')
    expect(document.querySelector('[data-infinia-menu]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })
})

describe('Combobox', () => {
  it('commits free text on change (datalist semantics)', () => {
    const onCommit = vi.fn()
    render(<Combobox value="" options={FRUITS} onCommit={onCommit} />)
    const input = screen.getByRole('combobox')
    fireEvent.change(input, { target: { value: '自定义列' } })
    expect(onCommit).toHaveBeenLastCalledWith('自定义列')
  })

  it('filters suggestions while typing and picks one', () => {
    const onCommit = vi.fn()
    render(<Combobox value="" options={FRUITS} onCommit={onCommit} />)
    const input = screen.getByRole('combobox')
    fireEvent.change(input, { target: { value: '樱' } })
    const options = document.querySelectorAll('[data-infinia-menu] [role="option"]')
    expect(options.length).toBe(1)
    fireEvent.click(options[0] as HTMLElement)
    expect(onCommit).toHaveBeenLastCalledWith('cherry')
  })

  it('navigates the FILTERED list with ArrowUp/ArrowDown and commits via Enter', () => {
    const onCommit = vi.fn()
    const options = [
      { value: 'apple', label: '苹果' },
      { value: 'apricot', label: '杏' },
      { value: 'banana', label: '香蕉' },
    ]
    render(<Combobox value="" options={options} onCommit={onCommit} />)
    const input = screen.getByRole('combobox')
    fireEvent.change(input, { target: { value: 'ap' } })
    // Filtered: apple, apricot. Typing seeds the cursor on the first suggestion.
    expect(document.querySelectorAll('[data-infinia-menu] [role="option"]').length).toBe(2)
    expect(input.getAttribute('aria-activedescendant')).toBe(
      document.querySelector('[data-option="apple"]')!.id,
    )
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    expect(input.getAttribute('aria-activedescendant')).toBe(
      document.querySelector('[data-option="apricot"]')!.id,
    )
    fireEvent.keyDown(input, { key: 'ArrowUp' })
    expect(input.getAttribute('aria-activedescendant')).toBe(
      document.querySelector('[data-option="apple"]')!.id,
    )
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onCommit).toHaveBeenLastCalledWith('apple')
  })

  it('never commits a disabled option through Enter in the filtered list', () => {
    const onCommit = vi.fn()
    const options = [
      { value: 'apple', label: '苹果', disabled: true },
      { value: 'apricot', label: '杏' },
    ]
    render(<Combobox value="" options={options} onCommit={onCommit} />)
    const input = screen.getByRole('combobox')
    fireEvent.change(input, { target: { value: 'a' } })
    // Filtered list starts at the disabled apple; Enter must not commit it.
    expect(input.getAttribute('aria-activedescendant')).toBe(
      document.querySelector('[data-option="apple"]')!.id,
    )
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onCommit).not.toHaveBeenCalledWith('apple')
    // ArrowDown skips past disabled options (cursor semantics over the filtered list).
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onCommit).toHaveBeenLastCalledWith('apricot')
  })

  it('announces the suggestion popup and returns focus to the input on close', async () => {
    const onCommit = vi.fn()
    render(<Combobox value="" options={FRUITS} onCommit={onCommit} />)
    const input = screen.getByRole('combobox')
    expect(input.getAttribute('aria-autocomplete')).toBe('list')
    expect(input.getAttribute('aria-expanded')).toBe('false')
    fireEvent.focus(input)
    expect(input.getAttribute('aria-expanded')).toBe('true')
    const listbox = document.querySelector('[data-infinia-menu][role="listbox"]') as HTMLElement
    expect(listbox).not.toBeNull()
    expect(input.getAttribute('aria-controls')).toBe(listbox.id)
    // Escape closes and leaves/focuses the input.
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(document.querySelector('[data-infinia-menu]')).toBeNull()
    expect(document.activeElement).toBe(input)
    // The close() focus-return guard clears on the next macrotask — a real user
    // cannot re-focus within it; the test must yield a tick before reopening.
    await new Promise((resolve) => setTimeout(resolve, 0))
    // Picking a suggestion returns focus to the input (blur first so the
    // focus() return is observable in jsdom, where clicks don't move focus).
    fireEvent.focus(input)
    fireEvent.blur(input)
    fireEvent.click(document.querySelector('[data-option="cherry"]') as HTMLElement)
    expect(onCommit).toHaveBeenLastCalledWith('cherry')
    expect(document.querySelector('[data-infinia-menu]')).toBeNull()
    expect(document.activeElement).toBe(input)
    // Outside click closes and restores focus to the input.
    fireEvent.focus(input)
    fireEvent.blur(input)
    fireEvent.mouseDown(document.body)
    expect(document.querySelector('[data-infinia-menu]')).toBeNull()
    expect(document.activeElement).toBe(input)
  })
})
