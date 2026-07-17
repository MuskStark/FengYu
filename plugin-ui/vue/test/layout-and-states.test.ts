import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import { createFengYuVuetify, FyErrorState, FyPluginShell } from '../src'

const global = { plugins: [createFengYuVuetify()] }

it('renders navigation and emits the selected item', async () => {
  const wrapper = mount(FyPluginShell, {
    global,
    props: { title: 'Workbench', modelValue: 'tasks', items: [
      { value: 'overview', title: 'Overview', icon: 'mdi-view-dashboard-outline' },
      { value: 'tasks', title: 'Tasks', icon: 'mdi-format-list-checks' },
    ] },
    slots: { default: '<main>Content</main>' },
  })
  expect(wrapper.text()).toContain('Workbench')
  await wrapper.find('[data-nav="overview"]').trigger('click')
  expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['overview'])
})

it('exposes a retry action with readable error text', async () => {
  const wrapper = mount(FyErrorState, { global, props: { title: 'Failed', message: 'Timed out' } })
  await wrapper.get('[data-action="retry"]').trigger('click')
  expect(wrapper.text()).toContain('Timed out')
  expect(wrapper.emitted('retry')).toHaveLength(1)
})

describe('FyPluginShell drawer breakpoint', () => {
  it('keeps the desktop rail permanent at plugin iframe widths', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1014 })
    window.dispatchEvent(new Event('resize'))
    const wrapper = mount(FyPluginShell, {
      global: { plugins: [createFengYuVuetify()] },
      props: {
        title: 'Workbench',
        modelValue: 'build',
        railBreakpoint: 720,
        items: [{ value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' }],
      },
      slots: { default: '<button data-content-action>Run</button>' },
    })
    await nextTick()

    const classes = wrapper.get('.v-navigation-drawer').classes()
    expect(classes).toContain('v-navigation-drawer--rail')
    expect(classes).not.toContain('v-navigation-drawer--temporary')
    expect(classes).not.toContain('v-navigation-drawer--mobile')
  })

  it('uses a temporary labeled drawer below the shell breakpoint', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 640 })
    window.dispatchEvent(new Event('resize'))
    const wrapper = mount(FyPluginShell, {
      global: { plugins: [createFengYuVuetify()] },
      props: {
        title: 'Workbench',
        modelValue: 'build',
        railBreakpoint: 720,
        items: [{ value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' }],
      },
    })
    await nextTick()

    expect(wrapper.get('.v-navigation-drawer').classes()).toContain('v-navigation-drawer--temporary')
    expect(wrapper.find('.v-app-bar-nav-icon').exists()).toBe(true)
  })
})
