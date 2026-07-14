import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import en from '../i18n/en'
import zhCN from '../i18n/zh-CN'

describe('Email Center responsive shell', () => {
  it('turns the task rail into a compact horizontally scrollable mobile navigation', () => {
    const css = fs.readFileSync(path.resolve('src/styles.css'), 'utf8')
    const mobile = css.match(/@media\s*\(max-width:\s*\d+px\)\s*\{([\s\S]*)\}\s*$/)?.[1] ?? ''

    expect(mobile).toMatch(/\.email-layout\s*\{[^}]*grid-template-columns:\s*1fr/)
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
