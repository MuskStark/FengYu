import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import {
  createFengYuVuetify,
  FyEmptyState,
  FyErrorState,
  FyPluginPage,
  FyPluginShell,
  FyProgress,
} from '../src'

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
  // The desktop drawer is expanded by default: nav item titles render as
  // readable labels alongside the icon. Default jsdom width is desktop.
  expect(wrapper.find('[data-nav="overview"]').exists()).toBe(true)
  await wrapper.find('[data-nav="overview"]').trigger('click')
  expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['overview'])
})

it('renders a nav icon as inline SVG when given path data', () => {
  // Path data (from @mdi/js) must render via FyIcon inline SVG; `mdi-*` names
  // use the separate Vuetify icon path covered below.
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

it('renders the CLI mdi icon name through Vuetify instead of as SVG path data', () => {
  const wrapper = mount(FyPluginShell, {
    global,
    props: {
      title: 'CLI plugin',
      modelValue: 'home',
      items: [{ value: 'home', title: 'Home', icon: 'mdi-home-outline' }],
    },
  })

  const item = wrapper.get('[data-nav="home"]')
  expect(item.find('svg.fy-icon').exists()).toBe(false)
  expect(item.find('.v-icon').exists()).toBe(true)
  expect(item.find('.v-icon').classes()).toContain('mdi-home-outline')
})

it('renders an empty-state mdi icon name through Vuetify', () => {
  const wrapper = mount(FyEmptyState, {
    global,
    props: { title: 'Empty', icon: 'mdi-inbox-outline' },
  })

  expect(wrapper.find('svg.fy-icon').exists()).toBe(false)
  expect(wrapper.find('.v-icon').exists()).toBe(true)
  expect(wrapper.find('.v-icon').classes()).toContain('mdi-inbox-outline')
})

it('exposes a retry action with readable error text', async () => {
  const wrapper = mount(FyErrorState, { global, props: { title: 'Failed', message: 'Timed out' } })
  await wrapper.get('[data-action="retry"]').trigger('click')
  expect(wrapper.text()).toContain('Timed out')
  expect(wrapper.emitted('retry')).toHaveLength(1)
})

it('renders a navigation-free shell for single-workspace plugins', () => {
  const wrapper = mount(FyPluginShell, {
    global,
    props: { title: 'Markdown' },
    slots: { default: '<main>Editor</main>' },
  })

  expect(wrapper.find('.v-navigation-drawer').exists()).toBe(false)
  expect(wrapper.find('.v-app-bar').exists()).toBe(false)
  expect(wrapper.text()).toContain('Editor')
  expect(wrapper.find('.fy-notification-center').exists()).toBe(true)
})

it('provides a responsive page frame and unified progress presentation', () => {
  const page = mount(FyPluginPage, {
    global,
    props: { maxWidth: 960, fullHeight: true },
    slots: { default: '<p>Content</p>' },
  })
  expect(page.classes()).toContain('fy-plugin-page')
  expect(page.classes()).toContain('fy-plugin-page--full-height')
  expect(page.attributes('style')).toContain('max-width: 960px')

  const progress = mount(FyProgress, {
    global,
    props: { label: 'Building', detail: '12 files', modelValue: 42, status: 'running' },
  })
  expect(progress.attributes('data-status')).toBe('running')
  expect(progress.text()).toContain('Building')
  expect(progress.text()).toContain('42%')
  expect(progress.get('.v-progress-linear').attributes('aria-valuenow')).toBe('42')
})

describe('FyPluginShell drawer breakpoint', () => {
  it('keeps the desktop drawer permanent at plugin iframe widths', async () => {
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

    // At/above the breakpoint the drawer is permanent (expanded by default —
    // not in rail mode) and never temporary or mobile.
    const classes = wrapper.get('.v-navigation-drawer').classes()
    expect(classes).not.toContain('v-navigation-drawer--rail')
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
  it('expands the desktop drawer with labels by default', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1014 })
    window.dispatchEvent(new Event('resize'))
    const wrapper = mount(FyPluginShell, {
      global: { plugins: [createFengYuVuetify()] },
      props: {
        title: 'Offline Python',
        modelValue: 'build',
        items: [{ value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' }],
      },
    })
    await nextTick()

    // Desktop drawer is expanded by default: the nav item title renders as a
    // readable label, and the drawer is NOT in rail mode.
    const drawer = wrapper.get('.v-navigation-drawer')
    expect(drawer.text()).toContain('Build')
    expect(drawer.classes()).not.toContain('v-navigation-drawer--rail')
    // The collapse toggle is rendered on desktop (mobile uses the app-bar hamburger).
    expect(wrapper.find('[data-action="toggle-rail"]').exists()).toBe(true)
  })

  it('collapses to an icon-only rail on toggle', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1014 })
    window.dispatchEvent(new Event('resize'))
    const wrapper = mount(FyPluginShell, {
      global: { plugins: [createFengYuVuetify()] },
      props: {
        title: 'Offline Python',
        modelValue: 'build',
        items: [{ value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' }],
      },
    })
    await nextTick()

    // Expanded by default — no rail class yet.
    expect(wrapper.get('.v-navigation-drawer').classes()).not.toContain('v-navigation-drawer--rail')
    await wrapper.get('[data-action="toggle-rail"]').trigger('click')
    await nextTick()
    // Collapsed — drawer enters Vuetify rail mode (icon-only).
    expect(wrapper.get('.v-navigation-drawer').classes()).toContain('v-navigation-drawer--rail')
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
