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
  expect(wrapper.get('.task-rail__brand').text()).toBe('Email Center')
  expect(wrapper.findAll('[aria-current="page"]')).toHaveLength(1)
  expect(wrapper.text()).toContain('写邮件')
  expect(wrapper.text()).not.toContain('Compose')
})

it('never stringifies unknown objects into user errors', () => {
  expect(actionable({ summary: 'Folder unavailable' }, 'Collect')).toContain('Folder unavailable')
  expect(actionable({ secret: 'hidden' }, 'Collect')).not.toContain('[object Object]')
  expect(actionable({ secret: 'hidden' }, 'Collect')).not.toContain('hidden')
})

it('uses the Codex visual system and both responsive breakpoints', () => {
  const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')
  expect(css).toMatch(/:root\s*\{[^}]*--email-canvas:\s*#[0-9a-f]{6}[^}]*--email-accent:\s*#2f8f57/i)
  expect(css).toMatch(/:root\[data-theme=['"]dark['"]\]\s*\{[^}]*--email-canvas:\s*#[0-9a-f]{6}[^}]*--email-accent:\s*#55b779/i)
  expect(css).toMatch(/\.email-layout\s*\{[^}]*grid-template-columns:\s*200px\s+minmax\(0,\s*1fr\)/)
  expect(css).toMatch(/\.task-rail__item\s*\{[^}]*flex-direction:\s*row/)
  expect(css).toMatch(/\.surface\s*\{[^}]*border-radius:\s*12px\s*!important/)
  expect(css).toMatch(/\.surface\s*\{[^}]*box-shadow:\s*0\s+1px\s+2px[^}]*!important/)
  expect(css).toMatch(/\.workspace-summary\s*\{[^}]*background:\s*var\(--email-surface-muted\)[^}]*box-shadow:\s*none\s*!important/)
  expect(css).toMatch(/\.editor:focus-within\s*\{[^}]*outline:\s*2px\s+solid\s+var\(--email-accent\)/)
  expect(css).toContain('@media (max-width: 1000px)')
  expect(css).toContain('@media (max-width: 720px)')
  expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\)\s*\{[\s\S]*animation-duration:\s*\.01ms\s*!important;[\s\S]*transition-duration:\s*\.01ms\s*!important;/)
  expect(css).toContain('rgb(var(--v-theme-background))')
  expect(css).toContain(':focus-visible')
})
