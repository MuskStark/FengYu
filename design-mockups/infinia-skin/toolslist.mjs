import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await ctx.addInitScript(() => localStorage.setItem('fengyu-theme', JSON.stringify('dark')))
const page = await ctx.newPage()
await page.goto('http://localhost:5173/tools', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
await page.waitForTimeout(1200)
const rows = await page.evaluate(() => {
  const cells = [...document.querySelectorAll('table tbody tr, [class*="tool"] [class*="row"], [class*="tool"] li')]
  return cells.slice(0, 30).map(c => c.textContent?.replace(/\s+/g, ' ').trim().slice(0, 60)).filter(Boolean)
})
console.log(JSON.stringify(rows, null, 1))
await browser.close()
