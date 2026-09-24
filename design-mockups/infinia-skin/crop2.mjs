import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
await ctx.addInitScript(() => localStorage.setItem('fengyu-theme', JSON.stringify('light')))
const page = await ctx.newPage()
const setup = async (path) => {
  await page.goto('http://localhost:5173' + path, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
  await page.addStyleTag({ content: readFileSync(join(here, 'inject.css'), 'utf8') })
  await page.evaluate(() => {
    const root = document.documentElement
    root.classList.remove('dark', 'v-theme--dark', 'theme-zai-dark')
    root.classList.add('v-theme--light', 'theme-zai-light')
  })
  await page.waitForTimeout(600)
}
// Tools page toolbar: search box + segmented filter + buttons
await setup('/tools')
const bar = await page.locator('.pg-toolbar, .toolbar, .pg-search').first().boundingBox()
if (bar) await page.screenshot({ path: join(here, 'crop-searchbar.png'), clip: { x: Math.max(0, bar.x - 24), y: Math.max(0, bar.y - 24), width: Math.min(760, bar.width + 48), height: bar.height + 48 } })
const inp = await page.locator('.pg-search .cx-input').first()
const st = await inp.evaluate(el => { const s = getComputedStyle(el); return { bg: s.backgroundColor, border: s.borderColor, radius: s.borderRadius } })
console.log('cx-input:', JSON.stringify(st))
// Store panel search
await setup('/store')
const ps = await page.locator('.pg-search .cx-input').first().evaluate(el => { const s = getComputedStyle(el); return { bg: s.backgroundColor, border: s.borderColor } }).catch(() => null)
console.log('store search:', JSON.stringify(ps))
await browser.close()
