import fs from 'node:fs'
import path from 'node:path'
import { beforeEach, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { actionable, applyEnvironment } from '../sdk'
import { i18n, syncLocale } from '../i18n'

beforeEach(() => setActivePinia(createPinia()))

it('localizes shared-shell navigation labels in Chinese', () => {
  applyEnvironment({ locale: 'zh-CN', theme: 'dark' }); syncLocale('zh-CN')
  const app = fs.readFileSync(path.resolve('src/App.vue'), 'utf8')
  expect(app).toContain('FyPluginShell')
  expect(app).toContain('navItems')
  expect(i18n.global.t('app.title')).toBe('邮件中心')
  expect(i18n.global.t('nav.compose')).toBe('写邮件')
})

it('never stringifies unknown objects into user errors', () => {
  expect(actionable({ summary: 'Folder unavailable' }, 'Collect')).toContain('Folder unavailable')
  expect(actionable({ secret: 'hidden' }, 'Collect')).not.toContain('[object Object]')
  expect(actionable({ secret: 'hidden' }, 'Collect')).not.toContain('hidden')
})

it('uses the Codex visual system and both responsive breakpoints', () => {
  const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')
  expect(css).not.toContain('--email-')
  const components = fs.readdirSync(path.resolve('src/components'))
    .filter(name => name.endsWith('.vue'))
    .map(name => fs.readFileSync(path.resolve('src/components', name), 'utf8'))
    .join('\n')
  expect(components).toContain('fy-surface')
  expect(css).toMatch(/\.editor:focus-within\s*\{[^}]*outline:\s*2px\s+solid\s+rgb\(var\(--v-theme-primary\)\)/)
  expect(css).toContain('@media (max-width: 1000px)')
  expect(css).toContain('@media (max-width: 720px)')
  expect(css).toContain('rgb(var(--v-theme-surface))')
})
