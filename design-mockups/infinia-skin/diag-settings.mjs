import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await ctx.addInitScript(() => localStorage.setItem('fengyu-theme', JSON.stringify('dark')))
const page = await ctx.newPage()
await page.goto('http://localhost:5174/settings', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
await page.addStyleTag({ content: readFileSync(join(here, 'inject.css'), 'utf8') })
await page.waitForTimeout(600)
const navTexts = await page.locator('.settings-nav button, .settings-nav a, nav button, aside button').allTextContents().catch(() => [])
console.log('nav candidates:', JSON.stringify(navTexts.filter(t => t && t.trim().length < 12).slice(0, 20)))
// find anything clickable with 外观/Appearance
const hit = page.getByText(/^(外观|Appearance)$/, { exact: true }).first()
const n = await hit.count()
console.log('appearance text found:', n)
if (n) { await hit.click().catch(e => console.log('click err', e.message)); await page.waitForTimeout(600) }
const state = await page.evaluate(() => {
  const cards = [...document.querySelectorAll('.cx-card, .theme-card, [class*="theme"]')]
  const sel = cards.filter(c => c.className.includes('selected') || c.className.includes('active'))
  return {
    cardCount: cards.length,
    selected: sel.map(c => ({ cls: c.className.slice(0, 60), border: getComputedStyle(c).borderColor })),
    toggles: [...document.querySelectorAll('.mcp-switch input:checked + span')].slice(0, 3).map(s => getComputedStyle(s).backgroundColor),
    h2: [...document.querySelectorAll('h2')].slice(0, 6).map(h => h.textContent?.trim()),
  }
})
console.log(JSON.stringify(state, null, 2))
await browser.close()
