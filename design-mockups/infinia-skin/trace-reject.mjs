import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const events = []
page.on('pageerror', e => events.push('PAGEERROR ' + String(e).slice(0, 300)))
await page.exposeFunction('__note', s => events.push(s))
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.evaluate(() => {
  window.addEventListener('unhandledrejection', e => window.__note('REJECT ' + String(e.reason?.stack || e.reason).slice(0, 400)))
  window.addEventListener('error', e => window.__note('ERROR ' + String(e.message).slice(0, 200)))
})
await page.waitForTimeout(1200)
await page.locator('.sidebar-primary-nav .cx-nav-item', { hasText: /工具/ }).first().click()
await page.waitForTimeout(1500)
console.log('url:', page.url())
console.log(events.length ? events.join('\n---\n') : 'no events captured')
await browser.close()
