import fs from 'node:fs'
import path from 'node:path'
import { beforeEach, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TaskRail from './TaskRail.vue'
import { actionable, applyEnvironment } from '../sdk'
import { i18n, syncLocale } from '../i18n'

beforeEach(() => setActivePinia(createPinia()))

it('renders one labelled current workspace in Chinese', () => {
  applyEnvironment({ locale: 'zh-CN', theme: 'dark' }); syncLocale('zh-CN')
  const wrapper = mount(TaskRail, { global: { plugins: [i18n] } })
  expect(wrapper.get('nav[aria-label="Email Center"]').element).toBeTruthy()
  expect(wrapper.findAll('[aria-current="page"]')).toHaveLength(1)
  expect(wrapper.text()).toContain('写邮件')
  expect(wrapper.text()).not.toContain('Compose')
})

it('never stringifies unknown objects into user errors', () => {
  expect(actionable({ summary: 'Folder unavailable' }, 'Collect')).toContain('Folder unavailable')
  expect(actionable({ secret: 'hidden' }, 'Collect')).not.toContain('[object Object]')
  expect(actionable({ secret: 'hidden' }, 'Collect')).not.toContain('hidden')
})

it('uses theme variables and both responsive breakpoints', () => {
  const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')
  expect(css).toContain('@media (max-width: 1000px)')
  expect(css).toContain('@media (max-width: 720px)')
  expect(css).toContain('rgb(var(--v-theme-background))')
  expect(css).toContain(':focus-visible')
})
