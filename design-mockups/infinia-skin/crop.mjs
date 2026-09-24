import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
await ctx.addInitScript(() => localStorage.setItem('fengyu-theme', JSON.stringify('light')))
const page = await ctx.newPage()
await page.goto('http://localhost:5174/', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
await page.addStyleTag({ content: readFileSync(join(here, 'inject.css'), 'utf8') })
await page.evaluate(() => {
  const root = document.documentElement
  root.classList.remove('dark', 'v-theme--dark', 'theme-zai-dark')
  root.classList.add('v-theme--light', 'theme-zai-light')
})
await page.waitForTimeout(600)
// sidebar account row crop (avatar signature) and composer crop
const acct = await page.locator('.sidebar-account').boundingBox()
if (acct) await page.screenshot({ path: join(here, 'crop-account.png'), clip: { x: acct.x - 4, y: acct.y - 4, width: acct.width + 8, height: acct.height + 8 } })
const comp = await page.locator('.cx-composer').boundingBox()
if (comp) await page.screenshot({ path: join(here, 'crop-composer.png'), clip: { x: comp.x - 10, y: comp.y - 10, width: comp.width + 20, height: comp.height + 20 } })
const nav = await page.locator('.sidebar-primary-nav').boundingBox()
if (nav) await page.screenshot({ path: join(here, 'crop-nav.png'), clip: { x: nav.x - 4, y: nav.y - 4, width: nav.width + 8, height: nav.height + 8 } })
console.log('crops done', !!acct, !!comp, !!nav)
await browser.close()
