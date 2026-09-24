import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await ctx.addInitScript(() => localStorage.setItem('fengyu-theme', JSON.stringify('light')))
const page = await ctx.newPage()
await page.goto('http://localhost:5174/store', { waitUntil: 'load', timeout: 30000 }).catch(() => {})
await page.waitForTimeout(2500) // let SPA routing settle
const css = readFileSync(join(here, 'inject.css'), 'utf8')
let injected = false
for (let i = 0; i < 5 && !injected; i++) {
  try { await page.addStyleTag({ content: css }); injected = true } catch { await page.waitForTimeout(600) }
}
await page.evaluate(() => {
  const root = document.documentElement
  root.classList.remove('dark', 'v-theme--dark', 'theme-zai-dark')
  root.classList.add('v-theme--light', 'theme-zai-light')
}).catch(async () => { await page.waitForTimeout(800); await page.evaluate(() => {
  const root = document.documentElement
  root.classList.remove('dark', 'v-theme--dark', 'theme-zai-dark')
  root.classList.add('v-theme--light', 'theme-zai-light')
}) })
await page.waitForTimeout(400)
const res = await page.evaluate(() => {
  const btn = document.querySelector('.cx-btn--primary')
  const sw = document.querySelector('.mcp-switch input:checked + span')
  const out = { found: !!btn }
  if (btn) { const s = getComputedStyle(btn); out.bg = s.backgroundColor; out.color = s.color; out.text = btn.textContent?.trim().slice(0, 10) }
  if (sw) out.switchOn = getComputedStyle(sw).backgroundColor
  out.url = location.pathname
  return out
})
console.log('injected:', injected, JSON.stringify(res))
await browser.close()
