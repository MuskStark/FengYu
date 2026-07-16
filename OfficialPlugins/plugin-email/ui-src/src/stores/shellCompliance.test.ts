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
    expect(main).toContain('createFengYuVuetify')
    expect(main).toContain('provideFengYuClient')
    // The app still wires Pinia + vue-i18n and mounts into #app.
    expect(main).toContain('createPinia')
    expect(main).toContain('.mount(\'#app\')')
  })

  it('keeps the email layout styling on top of the shared component system', () => {
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')

    // Layout grid + responsive task rail are email-specific CSS layered over the kit.
    expect(css).toContain('.email-layout')
    expect(css).toContain('.email-layout .v-field__outline')
    expect(css).toContain('.email-layout .v-btn--variant-tonal')
    expect(css).toContain('.email-layout .v-selection-control')
    expect(css).toContain('.v-overlay-container .v-list')
    expect(css).toContain('.v-overlay-container .v-card')
  })

  it('keeps forms spacious, buttons visible, and popups inside the component system', () => {
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')
    const components = fs.readdirSync(path.resolve('src/components'))
      .filter(name => name.endsWith('.vue'))
      .map(name => fs.readFileSync(path.resolve('src/components', name), 'utf8'))
      .join('\n')

    expect(css).toContain('.email-layout .v-card-text > :not(:first-child)')
    expect(css).toContain('.account-layout > :last-child > * + *')
    expect(css).toContain('.email-layout .v-btn--variant-elevated')
    expect(css).toContain('.v-card-actions .v-btn.text-primary')
    expect(css).toContain('.editor-toolbar .v-btn')
    expect(css).toContain('.codex-dialog')
    expect(css).toContain('.v-overlay-container .v-field__outline')
    expect(css).toContain('.v-overlay-container .v-btn--variant-elevated')
    expect(components).not.toMatch(/window\.(confirm|prompt)\s*\(/)
  })

  it('turns the task rail into a compact horizontally scrollable mobile navigation', () => {
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')
    const mobile = css.match(/@media\s*\(max-width:\s*720px\)\s*\{([\s\S]*)\}\s*$/)?.[1] ?? ''

    expect(mobile).toMatch(/\.email-layout\s*\{[^}]*display:\s*block/)
    expect(mobile).toMatch(/\.task-rail\s*\{[^}]*flex-direction:\s*row/)
    expect(mobile).toMatch(/\.task-rail\s*\{[^}]*overflow-x:\s*auto/)
    expect(mobile).toMatch(/\.task-rail__item\s*\{[^}]*flex:\s*0\s+0\s+auto/)
  })

  it('localizes the account-loading action in both message catalogs', () => {
    const app = fs.readFileSync(path.resolve('src/App.vue'), 'utf8')

    expect(app).not.toContain("'Loading accounts'")
    expect(app).toContain("t('accounts.loading')")
    expect(en.accounts.loading).toBe('Loading accounts')
    expect(zhCN.accounts.loading).toBe('正在加载账户')
  })
})
