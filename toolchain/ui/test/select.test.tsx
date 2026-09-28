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
})
