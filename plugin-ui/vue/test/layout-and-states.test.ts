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
  // The desktop rail is icon-only: the title is not rendered in the drawer
  // (it only appears in the mobile app bar). Default jsdom width is desktop.
  expect(wrapper.find('[data-nav="overview"]').exists()).toBe(true)
  await wrapper.find('[data-nav="overview"]').trigger('click')
  expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['overview'])
})

it('renders a nav icon as inline SVG when given path data', () => {
  // Path data (from @mdi/js) must render via FyIcon inline SVG, not the mdi
  // webfont — the font is not reliably bundled and shows tofu squares.
  const path = 'M13.78 15.3L19.78 21.3L21.89 19.14Z'
  const wrapper = mount(FyPluginShell, {
    global,
    props: { title: 'Workbench', modelValue: 'build', items: [
      { value: 'build', title: 'Build', icon: path },
    ] },
  })
  const item = wrapper.get('[data-nav="build"]')
  const svg = item.find('svg.fy-icon')
  expect(svg.exists()).toBe(true)
  expect(svg.find('path').attributes('d')).toBe(path)
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

describe('FyPluginShell refined rail chrome', () => {
  it('keeps the desktop rail icon-only with no title or brand label', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1014 })
    window.dispatchEvent(new Event('resize'))
    const wrapper = mount(FyPluginShell, {
      global: { plugins: [createFengYuVuetify()] },
      props: {
        title: 'Offline Python',
        brand: 'Offline Python',
        modelValue: 'build',
        items: [{ value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' }],
      },
    })
    await nextTick()

    // The rail is intentionally icon-only: neither a brand marker nor the
    // title text render inside the drawer on desktop (the mobile app bar still
    // shows the title in temporary mode).
    expect(wrapper.find('.fy-shell__brand').exists()).toBe(false)
    expect(wrapper.get('.v-navigation-drawer').text()).not.toContain('Offline Python')
  })

  it('marks the active nav item with a refinement hook class', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1014 })
    window.dispatchEvent(new Event('resize'))
    const wrapper = mount(FyPluginShell, {
      global: { plugins: [createFengYuVuetify()] },
      props: {
        title: 'Workbench',
        modelValue: 'build',
        items: [
          { value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' },
          { value: 'doctor', title: 'Doctor', icon: 'mdi-stethoscope' },
        ],
      },
    })
    await nextTick()

    const items = wrapper.findAll('[data-nav]')
    expect(items).toHaveLength(2)
    const active = wrapper.get('[data-nav="build"]')
    // Each item carries the refinement base class; the active one carries the
    // active modifier so the rail can paint a soft primary chip on it.
    expect(active.classes()).toContain('fy-shell__item')
    expect(active.classes()).toContain('fy-shell__item--active')
    expect(wrapper.get('[data-nav="doctor"]').classes()).not.toContain('fy-shell__item--active')
  })

  it('scopes the rail under a fy-shell class for refinement targeting', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1014 })
    window.dispatchEvent(new Event('resize'))
    const wrapper = mount(FyPluginShell, {
      global: { plugins: [createFengYuVuetify()] },
      props: {
        title: 'Workbench',
        modelValue: 'build',
        items: [{ value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' }],
      },
    })
    await nextTick()

    expect(wrapper.get('.v-navigation-drawer').classes()).toContain('fy-shell__rail')
  })
})
