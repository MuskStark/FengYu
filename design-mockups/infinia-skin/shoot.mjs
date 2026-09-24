// Screenshot the REAL frontend/ React app (Vite dev server) per route.
// Usage: node shoot.mjs <out-subdir> <light|dark> [inject.css] [route-filter]
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { mkdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, process.argv[2] ?? 'shots')
const theme = process.argv[3] ?? 'light'
const injectPath = process.argv[4]
const routeFilter = process.argv[5]
mkdirSync(outDir, { recursive: true })

const routes = [
  ['01-chat', '/'],
  ['02-flows', '/flows'],
  ['03-flowbuilder', '/flows/new'],
  ['04-schedules', '/schedules'],
  ['05-tools', '/tools'],
  ['06-store', '/store'],
  ['07-account', '/account'],
  ['08-settings', '/settings'],
  ['09-about', '/about'],
]

const browser = await chromium.launch({
  executablePath:
    '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
})
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 2,
})
await ctx.addInitScript(t => {
  localStorage.setItem('fengyu-theme', JSON.stringify(t))
}, theme)

const injectCss = injectPath ? readFileSync(join(here, injectPath), 'utf8') : null
const page = await ctx.newPage()

for (const [name, path] of routes) {
  if (routeFilter && !name.includes(routeFilter) && !path.includes(routeFilter)) continue
  await page.goto(`http://localhost:5173${path}`, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
  if (injectCss) {
    await page.addStyleTag({ content: injectCss }).catch(e => console.error('inject failed:', e.message))
  }
  // The settings store re-applies the BACKEND theme on load (overriding localStorage);
  // force the requested theme class for the shot without writing anything back.
  await page.evaluate(theme => {
    const root = document.documentElement
    root.classList.remove('dark', 'v-theme--dark', 'theme-zai-dark', 'v-theme--light', 'theme-zai-light')
    root.classList.add(theme === 'dark' ? 'v-theme--dark' : 'v-theme--light')
    root.classList.add(theme === 'dark' ? 'theme-zai-dark' : 'theme-zai-light')
    if (theme === 'dark') root.classList.add('dark')
  }, theme).catch(() => {})
  await page.waitForTimeout(400)
  if (path === '/settings') {
    // Default section (AI provider) carries no gold elements; open 外观/Appearance
    // so the theme-picker cards with their gold selected border are in the shot.
    await page.locator('button, [role="button"], a', { hasText: /^(外观|Appearance)$/ }).first().click().catch(() => {})
    await page.waitForTimeout(500)
  }
  await page.screenshot({ path: join(outDir, `${name}-${theme}.png`) })
  console.log('shot', name, theme)
}

await browser.close()
