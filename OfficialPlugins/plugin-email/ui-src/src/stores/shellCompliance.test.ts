import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { messages } from '../i18n'

/**
 * The React tree must run entirely on the official @infinia/* foundation:
 * the kit owns bootstrap, chrome, theming and i18n; the plugin only composes.
 */
describe('Email Center shell uses the official plugin-ui foundation', () => {
  it('bootstraps React + client via @infinia/plugin-ui', () => {
    const main = fs.readFileSync(path.resolve('src/main.tsx'), 'utf8')

    expect(main).toContain('mountFengYuApp')
    // The Vue bootstrap is gone; the kit owns client DI, i18n and mounting.
    expect(main).not.toContain('createPinia')
    expect(main).not.toContain('vue')
    expect(main).not.toContain('createI18n')
    // The app still wires its message tables through the shared bootstrap.
    expect(main).toContain('messages')
    expect(main).toContain('applyEnvironment')
  })

  it('uses the shared Infinia shell without a private color system', () => {
    const app = fs.readFileSync(path.resolve('src/App.tsx'), 'utf8')
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')

    expect(app).toContain('PluginShell')
    expect(app).toContain('PluginBar')
    expect(app).toContain('WORKSPACE_ICONS')
    expect(app).toContain('StatusBar')
    expect(css).not.toContain('--email-')
    expect(css).not.toContain('.email-layout')
    expect(css).toContain('var(--c-')
  })

  it('keeps popups inside the component system and forms on the shared tokens', () => {
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')
    const components = fs.readdirSync(path.resolve('src/components'))
      .filter(name => name.endsWith('.tsx'))
      .map(name => fs.readFileSync(path.resolve('src/components', name), 'utf8'))
      .join('\n')

    expect(components).not.toMatch(/window\.(confirm|prompt)\s*\(/)
    expect(css).toContain('.fy-card')
    expect(css).toContain('.editor-toolbar')
    expect(css).toContain('var(--c-gold)')
  })

  it('keeps business grids responsive below shared shell breakpoints', () => {
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')

    expect(css).toContain('@media (max-width: 1000px)')
    expect(css).toContain('@media (max-width: 720px)')
    expect(css).toMatch(/\.workspace-grid, \.batch-workspace, \.panel-grid, \.account-layout, \.archive-columns\s*\{[^}]*grid-template-columns:\s*1fr/)
    expect(css).toMatch(/\.form-grid\s*\{[^}]*grid-template-columns:\s*1fr/)
  })

  it('localizes the account-loading action in both message catalogs', () => {
    const app = fs.readFileSync(path.resolve('src/App.tsx'), 'utf8')

    expect(app).not.toContain("'Loading accounts'")
    expect(app).toContain("t('accounts.loading')")
    expect(messages.en['accounts.loading']).toBe('Loading accounts')
    expect(messages.zh['accounts.loading']).toBe('正在加载账户')
  })
})
