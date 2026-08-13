import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import en from '../i18n/en'
import zhCN from '../i18n/zh-CN'

describe('Email Center shell uses the official plugin-ui foundation', () => {
  it('bootstraps Vuetify + client via @infinia/plugin-ui', () => {
    const main = fs.readFileSync(path.resolve('src/main.ts'), 'utf8')

    // The hand-rolled createVuetify + bespoke themes are gone; the kit owns them.
    expect(main).not.toContain('emailLightTheme')
    expect(main).not.toContain('emailDarkTheme')
    expect(main).toContain('mountFengYuApp')
    // The shared lifecycle owns Vuetify, client DI, mounting and pagehide disposal.
    expect(main).not.toContain('createFengYuVuetify')
    expect(main).not.toContain('provideFengYuClient')
    // The app still wires Pinia + vue-i18n through the shared bootstrap.
    expect(main).toContain('createPinia')
    expect(main).toContain('plugins: [createPinia(), i18n]')
  })

  it('uses the shared responsive shell without a private color system', () => {
    const app = fs.readFileSync(path.resolve('src/App.vue'), 'utf8')
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')

    expect(app).toContain('FyPluginShell')
    expect(app).toContain('FyPluginPage')
    expect(css).not.toContain('--email-')
    expect(css).not.toContain('.email-layout')
    expect(css).toContain('rgb(var(--v-theme-surface-container-low))')
  })

  it('keeps forms spacious, buttons visible, and popups inside the component system', () => {
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')
    const components = fs.readdirSync(path.resolve('src/components'))
      .filter(name => name.endsWith('.vue'))
      .map(name => fs.readFileSync(path.resolve('src/components', name), 'utf8'))
      .join('\n')

    expect(css).toContain('.account-layout > :last-child > * + *')
    expect(css).toContain('.editor-toolbar .v-btn')
    expect(css).toContain('.codex-dialog')
    expect(components).not.toMatch(/window\.(confirm|prompt)\s*\(/)
  })

  it('keeps business grids responsive below shared shell breakpoints', () => {
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')

    expect(css).toContain('@media (max-width: 1000px)')
    expect(css).toContain('@media (max-width: 720px)')
    expect(css).toMatch(/\.workspace-grid, \.panel-grid, \.account-layout\s*\{[^}]*grid-template-columns:\s*1fr/)
    expect(css).toMatch(/\.form-grid\s*\{[^}]*grid-template-columns:\s*1fr/)
  })

  it('localizes the account-loading action in both message catalogs', () => {
    const app = fs.readFileSync(path.resolve('src/App.vue'), 'utf8')

    expect(app).not.toContain("'Loading accounts'")
    expect(app).toContain("t('accounts.loading')")
    expect(en.accounts.loading).toBe('Loading accounts')
    expect(zhCN.accounts.loading).toBe('正在加载账户')
  })
})
