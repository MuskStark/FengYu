import { test, expect, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import type { AgentTool, WorkflowDefinition } from '../../../frontend/src/services/types'

/*
 * Host regression cockpit — rewritten for the React frontend (commit 8d3f2624 cutover).
 * Each case maps to the React app's real structure (frontend/src):
 *
 *  1. runtime settings        → pages/SettingsPage.tsx section nav + components/settings/
 *                               RuntimeSection.tsx `details.cx-details` advanced editors;
 *                               the floating `.set-content` panel is the scroller.
 *  2. macOS settings nav      → shell/AppShell.tsx renders an in-flow 28px `.fx-windowbar`
 *                               strip on the settings route when platform os is darwin
 *                               (pinned via the `window.fengyu` desktop bridge init script).
 *  3. provider settings       → components/settings/AiProviderSection.tsx renders the
 *                               selected provider's fields as `input.cx-input`.
 *  4. flow library            → pages/FlowLibraryPage.tsx `.flow-library` scroller with
 *                               `article.flow-card` entries.
 *  5. store catalog tokens    → pages/StorePage.tsx + components/pages/InfiniaStorePanel.tsx
 *                               `.pg-card` grid (SpotlightCard carries the theme tokens).
 *  6. run dialog              → components/flow/FlowRunDialog.tsx (role=dialog, Esc closes,
 *                               builder keyboard shortcuts suppressed while it is open).
 *  7. plugin iframe bridge    → pages/PluginPage.tsx hosts the sandboxed iframe; a legacy
 *                               3.0.0-wire plugin must get theme/locale through the
 *                               negotiated-echo bridge, and an unknown protocol version
 *                               must be refused with a banner, not a silent drop.
 *
 * Behaviors removed after the React cutover (no current equivalent in frontend/src):
 *  - "hide idle scrollbars" (data-scrolling + transparent ::-webkit-scrollbar-thumb when
 *    idle): the React app ships always-visible native overlay scrollbars
 *    (`scrollbar-width: thin`, zai.css globals); the idle-hiding mechanism is gone. The
 *    case now asserts the scroller actually scrolls and keeps the thin-scrollbar styling.
 *  - store detail drawer + Teleport (`.store-detail`, `.store-list-item__main`): the React
 *    store has no detail drawer at all (inline install on catalog cards), so the teleported
 *    token-retention scenario no longer exists. The case asserts the equivalent intent on
 *    the catalog surface: host theme tokens reach the store cards and track theme switches.
 *  - run dialog `:modal` / focus trap / focus restore: FlowRunDialog is a plain div
 *    overlay (role="dialog" aria-modal="true") with no focus trap and no opener-refocus on
 *    close — Tab does not wrap and focus is not restored in the React implementation. The
 *    case keeps what exists: aria-modal, Esc close, canvas shortcut blocking, focus ring.
 *  - run dialog blocking Delete on the canvas: FIXED after being found as a React-cutover
 *    regression while porting this spec — @xyflow/react's window-level keydown listener
 *    ignored the overlay and deleted the selected node behind the dialog. FlowCanvas now
 *    takes deleteEnabled and FlowBuilderPage passes !runDialogOpen; the Delete assertion
 *    is back in the run-dialog case below.
 */

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
const storeEntry = {
  item: null, coordinate: 'infinia://plugin/rc/fixture', type: 'PLUGIN', namespace: 'rc', slug: 'fixture',
  name: 'RC plugin', summary: 'Store fixture', category: null, latestVersion: '1.0.0',
  installedVersion: null, installed: false,
}
const aiProvider = { endpoint: '', apiKey: '', apiKeySet: false, model: '' }

async function mockHost(page: Page, theme: 'dark' | 'light', language = 'zh') {
  const fixtures: Record<string, unknown> = {
    '/api/setup/status': { initialized: true },
    '/api/health': { status: 'ok' },
    '/api/settings': { theme, language, sidebarCollapsed: false },
    '/api/ai/config': {
      mode: 'openai', openai: aiProvider, anthropic: aiProvider, deepseek: aiProvider,
      ollama: { baseUrl: '', model: '' }, temperature: 0.7, topP: 1, maxTokens: 4096,
      maxToolRounds: 25, contextWindowTokens: 128000, toolLoadingMode: 'auto',
      toolLoadingThreshold: 25, systemPrompt: '',
    },
    '/api/account/me': { authenticated: false, userId: 'local', username: '', roles: [] },
    '/api/notifications/unread-count': { count: 0 },
    '/api/notifications': [],
    '/api/updates/check': { updateAvailable: false },
    '/api/workflows': workflows,
    '/api/workflows/rc-0': workflows[0],
    '/api/agent/tools': [tool],
    '/api/store/catalog': { items: [storeEntry], nextCursor: null },
    '/api/store/status': { apiBase: '' },
    '/api/security/process-isolation': { backend: 'fixture', sandboxed: true, reduced: false, compatibilityMode: false, lifecycleIsolation: 'strict', policy: 'strict' },
    '/api/plugin-runtime': [{
      id: 'dev.fengyu.smoke', name: 'Smoke Fixture', version: appVersion, permissions: [],
      uiEntry: '/plugin-runtime/dev.fengyu.smoke/ui/index.html',
    }, {
      id: 'dev.fengyu.future', name: 'Future Fixture', version: appVersion, permissions: [],
      uiEntry: '/plugin-runtime/dev.fengyu.future/ui/index.html',
    }],
  }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.fallback()
    if (path.endsWith('/invoke')) {
      const { method } = route.request().postDataJSON()
      return route.fulfill({ json: { success: false, summary: `Fixture does not implement ${method}` } })
    }
    if (path.endsWith('/ui-ticket')) {
      return route.fulfill({ json: { ticket: 'rc-ui-ticket' } })
    }
    if (route.request().method() !== 'GET') {
      return route.fulfill({ status: 503, json: { error: 'Read-only UI fixture' } })
    }
    return route.fulfill({ json: fixtures[path] ?? [] })
  })
}

/** Minimal `window.fengyu` desktop bridge so the React platform layer resolves to the
 *  Electron shell (kind: desktop, os: darwin) — same contract desktop.ts reads. */
async function installDarwinBridge(page: Page) {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'fengyu', {
      value: {
        desktop: true,
        platform: 'darwin',
        apiBase: () => location.origin,
        token: () => '',
        initialTheme: () => 'dark',
        setTheme: () => {},
        setupMode: () => false,
      },
    })
  })
}

/** Flip the theme through the production settings store (the real write path the
 *  AppearanceSection uses); PUT /api/settings is swallowed by the read-only fixture.
 *  The URL must match the app graph's module id EXACTLY (extension included): an
 *  extensionless import makes vite dev serve a SECOND module record, so the store
 *  would flip a duplicate instance — applyThemeClass still repaints the page, but no
 *  app subscriber (e.g. PluginPage's environment push) ever sees the change. */
async function changeTheme(page: Page, theme: 'dark' | 'light') {
  await page.evaluate(async value => {
    const { useSettingsStore } = await import('/src/stores/settings.ts')
    useSettingsStore.getState().setTheme(value)
  }, theme)
  await expect(page.locator('html')).toHaveClass(new RegExp(`\\bv-theme--${theme}\\b`))
}

/**
 * A stand-in app-4.0.x-era plugin UI: it posts host.ready stamped with the legacy 3.0.0
 * protocolVersion and — exactly like the old SDK's strict gate — drops every host envelope
 * not stamped 3.0.0, so the test proves the host negotiated the echo rather than hoping a
 * lenient fixture accepted a mismatched one. Everything it accepts is recorded on #state.
 */
function legacyPluginHtml(): string {
  return `<!doctype html><html><body><div id="state"></div><script>
    const state = document.getElementById('state')
    const shellOrigin = new URLSearchParams(location.search).get('shellOrigin') || '*'
    window.addEventListener('message', event => {
      const message = event.data
      if (!message || message.source !== 'fengyu-host' || message.protocolVersion !== '3.0.0') return
      if (message.type === 'response' && !state.dataset.readyVersion) {
        state.dataset.readyVersion = message.protocolVersion
        state.dataset.theme = message.result && message.result.theme
        state.dataset.locale = message.result && message.result.locale
      } else if (message.type === 'event' && message.event === 'environment') {
        state.dataset.eventVersion = message.protocolVersion
        state.dataset.eventTheme = message.data.theme
        state.dataset.eventLocale = message.data.locale
      }
    })
    parent.postMessage({ source: 'fengyu-plugin', type: 'request', protocolVersion: '3.0.0', id: 'legacy-ready-1', method: 'host.ready' }, shellOrigin)
  <\/script></body></html>`
}

for (const theme of ['dark', 'light'] as const) {
  test(`${theme}: runtime settings disclose advanced editors`, async ({ page }, testInfo) => {
    await mockHost(page, theme)
    await page.goto('/settings')
    await page.getByRole('button', { name: /运行时与安全/ }).click()
    const rules = page.locator('details.cx-details').first()
    const hooks = page.locator('details.cx-details').last()
    await expect(rules.locator('summary')).toHaveText(/权限规则/)
    await expect(rules.locator('textarea').first()).toBeHidden()
    await expect(hooks.locator('textarea')).toBeHidden()
    await page.screenshot({ path: testInfo.outputPath(`runtime-${theme}.png`), animations: 'disabled' })
    await rules.locator('summary').click()
    await expect(rules.locator('textarea').first()).toBeVisible()
    await hooks.locator('summary').focus()
    await page.keyboard.press('Enter')
    await expect(hooks.locator('textarea')).toBeVisible()
    // The floating .set-content panel is the scroller (settings.css); the opened hooks
    // editor must stay reachable inside it and the thin-scrollbar styling intact.
    const panel = page.locator('.set-content')
    await hooks.locator('textarea').scrollIntoViewIfNeeded()
    expect(await panel.evaluate(el => Math.floor(el.scrollTop))).toBeGreaterThan(0)
    expect(await panel.evaluate(el => getComputedStyle(el).scrollbarWidth)).toBe('thin')
    await expect(hooks.locator('textarea')).toBeInViewport()
  })

  test(`${theme}: macOS settings navigation stays below native window controls`, async ({ page }) => {
    await installDarwinBridge(page)
    await mockHost(page, theme)
    await page.goto('/settings')
    // On darwin the shell renders a 28px in-flow window-bar strip; the whole settings
    // surface starts below it so nothing ever underlays the traffic lights.
    const bar = page.locator('.fx-windowbar')
    await expect(bar).toBeVisible()
    const barBox = await bar.boundingBox()
    expect(barBox!.height).toBeLessThanOrEqual(28)
    const nav = page.locator('.set-nav')
    const back = page.locator('.set-nav-back-btn')
    await expect(back).toBeVisible()
    expect((await back.boundingBox())!.y).toBeGreaterThanOrEqual(barBox!.y + barBox!.height)
    const navTop = (await nav.boundingBox())!.y
    expect(navTop).toBe(barBox!.y + barBox!.height)
    await nav.evaluate(el => { el.scrollTop = el.scrollHeight })
    // The scroll viewport itself must never enter the native title-bar strip.
    expect((await nav.boundingBox())!.y).toBe(navTop)
    await expect(nav.locator('.set-nav-item').last()).toBeInViewport()
  })

  test(`${theme}: provider settings stay usable in the minimum desktop window`, async ({ page }, testInfo) => {
    await mockHost(page, theme, 'en')
    await page.goto('/settings')
    await page.locator('.set-nav-item').first().click()
    const detail = page.locator('.set-inner section').first()
    await expect(detail.getByRole('heading', { name: 'AI Providers' })).toBeVisible()
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

  test(`${theme}: store cards keep host tokens after theme changes`, async ({ page }) => {
    await mockHost(page, theme)
    await page.goto('/store')
    const card = page.locator('.pg-card', { hasText: 'RC plugin' }).first()
    await expect(card).toBeVisible()
    for (const next of [theme, theme === 'dark' ? 'light' : 'dark'] as const) {
      await changeTheme(page, next)
      const tokens = await page.evaluate(() => {
        const root = getComputedStyle(document.documentElement)
        const [br, bg, bb] = root.getPropertyValue('--cx-border').trim().replace('#', '').match(/.{2}/g)!
          .map(hex => Number.parseInt(hex, 16))
        return {
          border: `rgb(${br}, ${bg}, ${bb})`,
          surface: `rgb(${root.getPropertyValue('--v-theme-surface-container').trim().split(/[\s,]+/).join(', ')})`,
        }
      })
      // The catalog card rides the same host tokens the root just switched to: hairline
      // token border stays painted and the card surface fills with the theme surface.
      await expect(card).toHaveCSS('border-left-style', 'solid')
      await expect(card).toHaveCSS('border-left-color', tokens.border)
      await expect(card).toHaveCSS('background-color', tokens.surface)
    }
  })

  test(`${theme}: plugin iframe follows host theme and locale over the legacy wire version`, async ({ page }) => {
    await mockHost(page, theme)
    await page.route('**/plugin-runtime/dev.fengyu.smoke/**', async route => {
      await route.fulfill({ contentType: 'text/html', body: legacyPluginHtml() })
    })
    await page.goto('/plugin/dev.fengyu.smoke')
    const state = page.frameLocator('.plugin-frame').locator('#state')
    // The handshake must complete IN the plugin's dialect: the ready response carries the
    // negotiated version plus theme and locale — not a 3s timeout fallback to dark/en
    // (the regression this pins: legacy plugins hung then rendered on default theme).
    await expect(state).toHaveAttribute('data-ready-version', '3.0.0')
    await expect(state).toHaveAttribute('data-theme', theme)
    await expect(state).toHaveAttribute('data-locale', 'zh')
    // A theme flip through the real settings store must reach the frame as an environment
    // event the legacy strict gate accepts.
    const next = theme === 'dark' ? 'light' : 'dark'
    await changeTheme(page, next)
    await expect(state).toHaveAttribute('data-event-version', '3.0.0')
    await expect(state).toHaveAttribute('data-event-theme', next)
  })

  test(`${theme}: a plugin speaking an unknown protocol version is refused, not hung`, async ({ page }) => {
    await mockHost(page, theme)
    await page.route('**/plugin-runtime/dev.fengyu.future/**', async route => {
      await route.fulfill({
        contentType: 'text/html',
        body: `<!doctype html><body><script>
          parent.postMessage({ source: 'fengyu-plugin', type: 'request', protocolVersion: '9.9.9', id: 'future-1', method: 'host.ready' }, new URLSearchParams(location.search).get('shellOrigin') || '*')
        <\/script></body>`,
      })
    })
    await page.goto('/plugin/dev.fengyu.future')
    // Fail fast with the explicit incompatibility banner instead of silently dropping the
    // handshake and leaving the plugin on a spinner until its own ready() timeout.
    const banner = page.locator('.cx-alert--error')
    await expect(banner).toBeVisible()
    await expect(banner).toContainText('9.9.9')
    await expect(banner).toContainText('4.0.0')
  })

  test(`${theme}: run dialog blocks canvas keys and closes on Escape`, async ({ page }, testInfo) => {
    await mockHost(page, theme)
    await page.goto('/flows/rc-0')
    const opener = page.locator('.flow-toolbar > .flow-run-button')
    await expect(opener).toBeEnabled()
    await page.locator('.flow-tool-node').first().click()
    const nodeCount = await page.locator('.react-flow__node').count()
    expect(nodeCount).toBeGreaterThan(1)
    await opener.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toHaveAttribute('aria-modal', 'true')
    // The run-input field carries the themed focus ring (2px outline + token border).
    const input = dialog.locator('input.cx-input').first()
    await input.click()
    await expect(input).toHaveCSS('outline-style', 'solid')
    await expect(input).toHaveCSS('outline-width', '2px')
    const focusBorder = await input.evaluate(el => getComputedStyle(el).getPropertyValue('--cx-input-focus').trim())
    const [fr, fg, fb] = focusBorder.replace('#', '').match(/.{2}/g)!.map(hex => Number.parseInt(hex, 16))
    await expect(input).toHaveCSS('border-top-color', `rgb(${fr}, ${fg}, ${fb})`)
    await dialog.locator('.flow-run-dialog__close').focus()
    // Canvas shortcuts are suppressed while the dialog is open: 'n' must not open the
    // palette (FlowBuilderPage's shortcut handler early-returns on runDialogOpen), and
    // Delete must not remove the selected canvas node — @xyflow/react listens at window
    // level, so FlowCanvas disables deleteKeyCode entirely while the dialog is open
    // (deleteEnabled={!runDialogOpen}; Vue-era native <dialog> modality used to provide
    // this for free). Regression found while porting this spec, now pinned.
    const canvasNodes = page.locator('.flow-tool-node')
    const canvasNodesBefore = await canvasNodes.count()
    await page.keyboard.press('Delete')
    await expect(canvasNodes).toHaveCount(canvasNodesBefore)
    await page.keyboard.press('n')
    await expect(page.locator('.flow-panel--left')).toBeHidden()
    await page.screenshot({ path: testInfo.outputPath(`run-dialog-${theme}.png`) })
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await opener.click()
    await expect(dialog).toBeVisible()
    await dialog.locator('.flow-run-dialog__close').click()
    await expect(dialog).toHaveCount(0)
  })
}
