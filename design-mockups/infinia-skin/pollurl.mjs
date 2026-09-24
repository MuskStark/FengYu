import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1500)
await page.evaluate(() => {
  window.__urls = [location.pathname]
  const push = history.pushState.bind(history), rep = history.replaceState.bind(history)
  history.pushState = function (...a) { window.__urls.push('P' + String(a[2]).slice(0, 30)); return push(...a) }
  history.replaceState = function (...a) { window.__urls.push('R' + String(a[2]).slice(0, 30)); return rep(...a) }
  window.addEventListener('popstate', () => window.__urls.push('pop→' + location.pathname))
  setInterval(() => { if (window.__urls[window.__urls.length - 1] !== location.pathname) window.__urls.push('poll→' + location.pathname) }, 40)
})
await page.locator('.sidebar-primary-nav .cx-nav-item', { hasText: /工具/ }).first().click()
await page.waitForTimeout(2000)
console.log(JSON.stringify(await page.evaluate(() => window.__urls)))
await browser.close()
