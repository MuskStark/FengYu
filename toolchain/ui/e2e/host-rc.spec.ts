import { test, expect, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import type { AgentTool, WorkflowDefinition } from '../../../frontend/src/api/types'

const pluginNames = ['markdown', 'excel', 'email', 'offlinepython'] as const
const appVersion = JSON.parse(await readFile(new URL('../../../frontend/package.json', import.meta.url), 'utf8')).version
const browserErrors = new WeakMap<Page, string[]>()

test.beforeEach(({ page }) => {
  const errors: string[] = []
  browserErrors.set(page, errors)
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => {
    if (message.type() === 'error' && /\b(TypeError|ReferenceError|SyntaxError)\b/.test(message.text())) {
      errors.push(message.text())
    }
  })
})

test.afterEach(({ page }) => {
  expect(browserErrors.get(page)).toEqual([])
})
const tool: AgentTool = {
  id: 'rc_echo', name: 'rc_echo', description: 'RC test tool', revision: '1',
  inputSchema: JSON.stringify({ type: 'object', properties: { subject: { type: 'string' } } }),
}
const workflows = Array.from({ length: 40 }, (_, index) => ({
  id: `rc-${index}`, name: `RC workflow ${index}`, description: 'UI regression fixture',
  inputSchema: { type: 'object', properties: { subject: { type: 'string', title: 'Subject' } } },
  plan: { goal: 'RC review', reasoning: '', steps: [{ index: 0, toolName: tool.name, description: 'RC test', args: { subject: '{{inputs.subject}}' }, dependsOn: [], requiresApproval: false, status: 'pending' }] },
  published: false, revision: 1, createdAt: '2026-09-08T00:00:00Z', updatedAt: '2026-09-08T00:00:00Z',
} satisfies WorkflowDefinition))
const listing = {
  coordinate: 'infinia://plugin/rc/fixture', type: 'PLUGIN', namespace: 'rc', slug: 'fixture',
  name: 'RC plugin', summary: 'Store fixture', installed: false, latestVersion: '1.0.0',
}

async function mockHost(page: Page, theme: 'dark' | 'light', language = 'zh') {
  const provider = { endpoint: '', apiKey: '', apiKeySet: false, model: '' }
  const fixtures: Record<string, unknown> = {
    '/api/setup/status': { initialized: true },
    '/api/health': { status: 'ok' },
    '/api/settings': { theme, language, sidebarCollapsed: false },
    '/api/ai/config': { mode: 'openai', openai: provider, anthropic: provider, deepseek: provider, ollama: { baseUrl: '', model: '' } },
    '/api/account/me': { authenticated: false, userId: 'local', username: '' },
    '/api/notifications/unread-count': { count: 0 },
    '/api/updates/check': { updateAvailable: false },
    '/api/workflows': workflows,
    '/api/workflows/rc-0': workflows[0],
    '/api/agent/tools': [tool],
    '/api/store/catalog': [listing],
    '/api/store/status': { apiBase: '' },
    '/api/store/listings/rc/fixture': { ...listing, status: 'PUBLISHED', defaultChannel: 'stable', tags: [], downloads: 0, releases: [] },
    '/api/plugin-runtime': pluginNames.map(name => ({
      id: `fan.summer.${name}`, name, version: appVersion, permissions: [],
      uiEntry: `/plugin-runtime/fan.summer.${name}/ui/index.html`,
    })),
  }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.fallback()
    if (path.endsWith('/invoke')) {
      // Only the initial render/account-list reads are needed by these UI cases.
      const { method } = route.request().postDataJSON()
      const result = method === 'render'
        ? { success: true, html: '<p>Markdown preview</p>' }
        : method === 'email_accounts_list'
          ? { success: true, accounts: [] }
          : { success: false, summary: `Fixture does not implement ${method}` }
      return route.fulfill({ json: result })
    }
    if (route.request().method() !== 'GET') {
      return route.fulfill({ status: 503, json: { error: 'Read-only UI fixture' } })
    }
    return route.fulfill({ json: fixtures[path] ?? [] })
  })
}

async function changeTheme(page: Page, theme: 'dark' | 'light') {
  // Use the production store so the real PluginView environment bridge also runs.
  await page.evaluate(async value => {
    const modulePath = '/src/stores/theme.ts'
    const { useThemeStore } = await import(modulePath)
    useThemeStore().setTheme(value)
  }, theme)
  await expect(page.locator('.cx-root')).toHaveClass(new RegExp(`v-theme--${theme}`))
}

for (const theme of ['dark', 'light'] as const) {
  test(`${theme}: runtime settings disclose advanced editors and hide idle scrollbars`, async ({ page }, testInfo) => {
    await mockHost(page, theme)
    await page.goto('/settings')
    await page.getByRole('button', { name: /运行时与安全/ }).click()
    const rules = page.locator('.guard-advanced').first()
    const hooks = page.locator('.guard-advanced').last()
    await expect(rules.locator('textarea').first()).toBeHidden()
    await expect(hooks.locator('textarea')).toBeHidden()
    await page.screenshot({ path: testInfo.outputPath(`runtime-${theme}.png`), animations: 'disabled' })
    await rules.locator('summary').click()
    await expect(rules.locator('textarea').first()).toBeVisible()
    await hooks.locator('summary').focus()
    await page.keyboard.press('Enter')
    await expect(hooks.locator('textarea')).toBeVisible()
    const area = page.locator('.set-inner')
    await area.evaluate(el => { el.scrollTop = el.scrollHeight })
    await expect(area).toHaveAttribute('data-scrolling', 'true')
    await expect(area).not.toHaveAttribute('data-scrolling')
    expect(await area.evaluate(el => getComputedStyle(el, '::-webkit-scrollbar-thumb').backgroundColor)).toBe('rgba(0, 0, 0, 0)')
  })

  test(`${theme}: macOS settings navigation stays below native window controls`, async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(window, 'fengyu', {
        value: { platform: 'darwin', apiBase: () => location.origin, token: () => '', setupMode: () => false, initialTheme: () => 'dark', setTheme: () => {} },
      })
    })
    await mockHost(page, theme)
    await page.goto('/settings')
    const nav = page.locator('.set-nav')
    const back = page.locator('.set-nav-back-btn')
    await expect(back).toBeVisible()
    expect((await back.boundingBox())!.y).toBeGreaterThanOrEqual(48)
    expect((await nav.boundingBox())!.y).toBe(48)
    await nav.evaluate(el => { el.scrollTop = el.scrollHeight })
    // The scroll viewport itself must never enter the native title-bar strip.
    expect((await nav.boundingBox())!.y).toBe(48)
    await expect(nav.locator('.set-nav-item').last()).toBeInViewport()
  })

  test(`${theme}: provider settings stay usable in the minimum desktop window`, async ({ page }, testInfo) => {
    await mockHost(page, theme, 'en')
    await page.goto('/settings')
    await page.locator('.set-nav-item').first().click()
    const detail = page.locator('.prov-detail')
    await expect(detail).toBeVisible()
    const field = detail.locator('input.cx-input').first()
    await expect(field).toBeEditable()
    const bounds = await field.boundingBox()
    expect(bounds!.width).toBeGreaterThan(180)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(960)
    await page.screenshot({ path: testInfo.outputPath(`settings-${theme}.png`), animations: 'disabled' })
  })

  test(`${theme}: last workflow remains reachable in the minimum desktop window`, async ({ page }) => {
    await mockHost(page, theme)
    await page.goto('/flows')
    const library = page.locator('.flow-library')
    const last = library.locator('article.flow-card').last()
    await expect(last).toContainText('RC workflow 39')
    await library.hover()
    await page.mouse.wheel(0, 10000)
    await expect(last).toBeInViewport()
    expect(await library.evaluate(el => el.scrollTop)).toBeGreaterThan(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(960)
  })

  test(`${theme}: store detail retains host tokens while teleported and after theme changes`, async ({ page }) => {
    await mockHost(page, theme)
    await page.goto('/store')
    await page.locator('.store-list-item__main').click()
    const drawer = page.locator('.store-detail')
    await expect(drawer).toBeVisible()
    for (const next of [theme, theme === 'dark' ? 'light' : 'dark'] as const) {
      await changeTheme(page, next)
      const host = await page.locator('.cx-root').evaluate(el => {
        const style = getComputedStyle(el)
        return { color: style.color, border: style.getPropertyValue('--cx-border-subtle').trim() }
      })
      await expect(drawer).toHaveCSS('color', host.color)
      expect(await drawer.evaluate(el => getComputedStyle(el).getPropertyValue('--cx-border-subtle').trim())).toBe(host.border)
      expect(await drawer.evaluate(el => getComputedStyle(el).borderLeftStyle)).toBe('solid')
    }
  })

  test(`${theme}: run dialog traps focus, blocks canvas keys, and restores its opener`, async ({ page }, testInfo) => {
    await mockHost(page, theme)
    await page.goto('/flows/rc-0')
    const opener = page.locator('.flow-toolbar > .flow-run-button')
    await expect(opener).toBeEnabled()
    await page.locator('.vue-flow__node-tool').first().click()
    const nodeCount = await page.locator('.vue-flow__node').count()
    await opener.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    expect(await dialog.evaluate(el => el.matches(':modal'))).toBe(true)
    // Tab/Shift+Tab must wrap without reaching navigation or the canvas.
    for (let index = 0; index < 12; index++) {
      await page.keyboard.press(index < 6 ? 'Tab' : 'Shift+Tab')
      expect(await dialog.evaluate(el => el.contains(document.activeElement))).toBe(true)
    }
    const input = dialog.locator('input.cx-input').first()
    await input.focus()
    await expect(input).toHaveCSS('outline-style', 'solid')
    await expect(input).toHaveCSS('outline-width', '2px')
    const primary = await input.evaluate(el => `rgb(${getComputedStyle(el).getPropertyValue('--v-theme-primary').trim().split(',').map(x => x.trim()).join(', ')})`)
    await expect(input).toHaveCSS('border-top-color', primary)
    await dialog.locator('.flow-run-dialog__close').focus()
    await page.keyboard.press('n')
    await page.keyboard.press('Delete')
    expect(await page.locator('.vue-flow__node').count()).toBe(nodeCount)
    await expect(page.locator('.flow-panel--left')).toBeHidden()
    await page.screenshot({ path: testInfo.outputPath(`run-dialog-${theme}.png`) })
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(opener).toBeFocused()
    await opener.click()
    await expect(dialog).toBeVisible()
    await dialog.locator('.flow-run-dialog__close').click()
    await expect(opener).toBeFocused()
  })
}

for (const name of pluginNames) {
  test(`${name}: built plugin follows live host theme and warning semantics`, async ({ page }, testInfo) => {
    await mockHost(page, 'dark')
    await page.route(new RegExp(`/plugin-runtime/fan\\.summer\\.${name}/ui/`), async route => {
      const pathname = new URL(route.request().url()).pathname
      const relative = pathname.split('/ui/')[1]
      const asset = fileURLToPath(new URL(`../../../OfficialPlugins/plugin-${name}/ui-src/dist/${relative}`, import.meta.url))
      const body = await readFile(asset)
      const contentType = relative.endsWith('.html') ? 'text/html'
        : relative.endsWith('.js') ? 'text/javascript'
          : relative.endsWith('.css') ? 'text/css' : 'application/octet-stream'
      await route.fulfill({ body, contentType })
    })
    await page.goto(`/plugin/fan.summer.${name}`)
    const frame = page.frameLocator('.plugin-frame')
    const app = frame.locator('.v-application')
    await expect(app).toBeVisible()
    await app.evaluate(el => el.setAttribute('data-rc-mounted', 'true'))
    for (const theme of ['dark', 'light'] as const) {
      await changeTheme(page, theme)
      const primary = await page.locator('.cx-root').evaluate(el => getComputedStyle(el).getPropertyValue('--v-theme-primary').trim())
      await expect.poll(() => app.evaluate(el => getComputedStyle(el).getPropertyValue('--v-theme-primary').trim())).toBe(primary)
      const colors = await app.evaluate(el => {
        const warning = document.createElement('span')
        warning.className = 'fy-status fy-status--warning'
        const error = document.createElement('span')
        error.className = 'fy-status fy-status--error'
        el.append(warning, error)
        const result = { warning: getComputedStyle(warning).color, error: getComputedStyle(error).color }
        warning.remove()
        error.remove()
        return result
      })
      const hostWarning = await page.locator('.cx-root').evaluate(el => {
        const chip = document.createElement('span')
        chip.className = 'cx-chip cx-chip--warn'
        el.append(chip)
        const color = getComputedStyle(chip).color
        chip.remove()
        return color
      })
      expect(colors.warning).toBe(hostWarning)
      expect(colors.warning).not.toBe(colors.error)
      await expect(app).toHaveAttribute('data-rc-mounted', 'true')
      await page.screenshot({ path: testInfo.outputPath(`${name}-${theme}.png`), animations: 'disabled' })
      for (const button of await frame.locator('.v-btn.bg-primary:not(:disabled)').all()) {
        await expect(button).toHaveCSS('color', theme === 'dark' ? 'rgb(13, 13, 13)' : 'rgb(255, 255, 255)')
      }
    }
    if (name === 'markdown') {
      await expect(frame.locator('.fy-page-header__title')).toHaveCount(1)
      await expect(frame.locator('.v-card-title')).toHaveCount(0)
      await frame.locator('.mde-textarea').focus()
      await expect(frame.locator('.mde-textarea')).toHaveCSS('outline-width', '2px')
    }
  })
}
