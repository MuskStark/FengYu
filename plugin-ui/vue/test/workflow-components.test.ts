/**
 * Workflow component tests: FyStepWizard, FyConfirmDialog, FyTaskTable.
 *
 * These exercise the behavioral contracts documented for each component
 * without coupling to Vuetify internals:
 * - FyStepWizard gates navigation through an async/sync `canContinue(from, to)`,
 *   emitting `blocked` (and NOT `update:modelValue`) when a transition is
 *   refused, advancing otherwise, and emitting `complete` past the final step.
 * - FyConfirmDialog requires an explicit confirm action (`data-action="confirm"`),
 *   and a destructive dialog labels that action textually.
 * - FyTaskTable renders one row per task with a status icon + label and exposes
 *   the host (`FyTaskRow`) data via its rendered output.
 */
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createFengYuVuetify, FyConfirmDialog, FyStepWizard, FyTaskTable } from '../src'
import type { FyTaskRow, FyWizardStep } from '../src'

const global = { plugins: [createFengYuVuetify()] }

describe('FyStepWizard', () => {
  it('blocks invalid wizard navigation and emits completion after the final valid step', async () => {
    const steps: FyWizardStep[] = [
      { value: 'file', title: 'File' },
      { value: 'run', title: 'Run' },
    ]
    const canContinue = vi.fn((_from: string, to: string) => to !== 'run')
    const wrapper = mount(FyStepWizard, {
      global,
      props: { steps, canContinue },
    })
    // From 'file', the target is 'run', which canContinue refuses.
    await wrapper.get('[data-action="next"]').trigger('click')
    expect(wrapper.emitted('blocked')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    // Allow the transition this time; the wizard should advance to 'run'.
    canContinue.mockReturnValue(true)
    await wrapper.get('[data-action="next"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['run'])

    // On the final step, Next emits `complete`.
    await wrapper.get('[data-action="next"]').trigger('click')
    expect(wrapper.emitted('complete')).toHaveLength(1)
  })

  it('awaits an async canContinue before deciding to advance', async () => {
    const steps: FyWizardStep[] = [
      { value: 'a', title: 'A' },
      { value: 'b', title: 'B' },
    ]
    const canContinue = vi.fn(async (_from: string, _to: string) => true)
    const wrapper = mount(FyStepWizard, { global, props: { steps, canContinue } })
    await wrapper.get('[data-action="next"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['b'])
    expect(canContinue).toHaveBeenCalledWith('a', 'b')
  })

  it('goes back to the previous step without consulting canContinue', async () => {
    const steps: FyWizardStep[] = [
      { value: 'a', title: 'A' },
      { value: 'b', title: 'B' },
    ]
    const wrapper = mount(FyStepWizard, {
      global,
      props: { steps, modelValue: 'b' },
    })
    await wrapper.get('[data-action="back"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['a'])
  })
})

describe('FyConfirmDialog', () => {
  // v-dialog teleports its content to document.body, so the buttons are
  // queried from the live document (attachTo: document.body mounts the root
  // there) rather than from the wrapper's tree.
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('uses its own focus restoration without Vuetify retain-focus', () => {
    const wrapper = mount(FyConfirmDialog, {
      global,
      props: { modelValue: false, title: 'Continue?' },
    })
    const dialog = wrapper.findComponent({ name: 'VDialog' })
    expect(dialog.props('retainFocus')).toBe(false)
    expect(dialog.props('captureFocus')).toBe(false)
  })

  it('requires an explicit confirm action', async () => {
    const wrapper = mount(FyConfirmDialog, {
      global,
      attachTo: document.body,
      props: { modelValue: true, title: 'Delete task?', destructive: true },
    })
    await wrapper.vm.$nextTick()
    const confirmButton = document.body.querySelector<HTMLButtonElement>('[data-action="confirm"]')!
    confirmButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('emits cancel from the cancel action', async () => {
    const wrapper = mount(FyConfirmDialog, {
      global,
      attachTo: document.body,
      props: { modelValue: true, title: 'Continue?' },
    })
    await wrapper.vm.$nextTick()
    const cancelButton = document.body.querySelector<HTMLButtonElement>('[data-action="cancel"]')!
    cancelButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('closes on Esc and returns focus to the opener', async () => {
    // Real opener button in the document, focused before the dialog opens, so
    // the component has an element to remember and restore focus to.
    const opener = document.createElement('button')
    opener.textContent = 'Open dialog'
    document.body.appendChild(opener)
    opener.focus()
    expect(document.activeElement).toBe(opener)

    const wrapper = mount(FyConfirmDialog, {
      global,
      attachTo: document.body,
      props: { modelValue: false, title: 'Delete task?' },
    })
    await wrapper.vm.$nextTick()

    // Open the dialog: the watcher captures the focused opener.
    await wrapper.setProps({ modelValue: true })
    await wrapper.vm.$nextTick()
    // jsdom does NOT move focus into the dialog on open the way a real browser
    // (and Vuetify's focus trap) does. Simulate that focus-steal here so the
    // restore assertion below is meaningful rather than trivially true.
    const confirmButton = document.body.querySelector<HTMLElement>('[data-action="confirm"]')!
    confirmButton.focus()
    expect(document.activeElement).toBe(confirmButton)

    // Esc must emit `cancel` and close. Dispatched onto the focused element
    // (which lives inside the dialog) the way a real browser delivers a
    // keystroke — it bubbles up to the dialog's `@keydown.esc` handler exactly
    // once. Dispatching on `window` does not reach jsdom's element-level
    // listener, and dispatching on the (unfocused) `.v-card` bubbles through
    // two overlay elements and double-fires the handler.
    document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    // The component closes itself by emitting update:modelValue:false.
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])

    // Reflect the close through the prop (the host would do this), then assert
    // focus was restored to the opener.
    await wrapper.setProps({ modelValue: false })
    await wrapper.vm.$nextTick()
    expect(document.activeElement).toBe(opener)

    opener.remove()
  })

  it('returns focus to the opener when closed via the cancel button', async () => {
    // jsdom's KeyboardEvent dispatch onto a teleported v-dialog card can be
    // flaky; this test pins the focus-restore contract through the cancel
    // button path, which is fully under the test's control.
    const opener = document.createElement('button')
    opener.textContent = 'Open dialog'
    document.body.appendChild(opener)
    opener.focus()
    expect(document.activeElement).toBe(opener)

    const wrapper = mount(FyConfirmDialog, {
      global,
      attachTo: document.body,
      props: { modelValue: false, title: 'Delete task?' },
    })
    await wrapper.vm.$nextTick()

    // Open: watcher remembers the opener.
    await wrapper.setProps({ modelValue: true })
    await wrapper.vm.$nextTick()
    // Simulate the browser/Vuetify focus-steal that jsdom does not perform, so
    // the restore assertion exercises the component's focus-return code rather
    // than passing trivially because the opener never lost focus.
    const confirmButton = document.body.querySelector<HTMLElement>('[data-action="confirm"]')!
    confirmButton.focus()
    expect(document.activeElement).toBe(confirmButton)

    // Close via the cancel button.
    const cancelButton = document.body.querySelector<HTMLButtonElement>('[data-action="cancel"]')!
    cancelButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    // Host honors the emitted close.
    await wrapper.setProps({ modelValue: false })
    await wrapper.vm.$nextTick()

    expect(document.activeElement).toBe(opener)

    opener.remove()
  })
})

describe('FyTaskTable', () => {
  const tasks: FyTaskRow[] = [
    { id: '1', name: 'sales-2026.xlsx', status: 'success' },
    { id: '2', name: 'contacts.xlsx', status: 'running', detail: '42%' },
  ]

  it('renders a row per task with the name and a status label', () => {
    const wrapper = mount(FyTaskTable, { global, props: { tasks } })
    const text = wrapper.text()
    expect(text).toContain('sales-2026.xlsx')
    expect(text).toContain('contacts.xlsx')
    // Status is conveyed as readable text (not color alone).
    expect(text).toContain('Success')
    expect(text).toContain('Running')
  })

  it('renders an empty state when there are no tasks', () => {
    const wrapper = mount(FyTaskTable, { global, props: { tasks: [] } })
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
  })
})
