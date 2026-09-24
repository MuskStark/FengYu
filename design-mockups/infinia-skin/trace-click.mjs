import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 20000 }).catch(() => {})
await page.waitForTimeout(1800)
await page.evaluate(() => {
  window.__trace = []
  const push = history.pushState.bind(history)
  history.pushState = (...a) => { window.__trace.push('pushState ' + String(a[2])); return push(...a) }
  const rep = history.replaceState.bind(history)
  history.replaceState = (...a) => { window.__trace.push('replaceState ' + String(a[2])); return rep(...a) }
  document.addEventListener('click', e => {
    const t = e.target
    window.__trace.push('click ' + t.tagName + '.' + String(t.className).slice(0, 34) + ' defaultPrevented=' + e.defaultPrevented)
  }, true)
})
await page.click('.sidebar-user-button')
await page.waitForTimeout(400)
const item = page.locator('.sidebar-account-menu-item', { hasText: /设置|Settings/ }).first()
await item.click()
await page.waitForTimeout(1800)
const trace = await page.evaluate(() => ({
  trace: window.__trace,
  url: location.pathname,
  dialogOpen: !!document.querySelector('[role="dialog"], .app-dialog, .cx-dialog, [class*="overlay"]'),
}))
console.log(JSON.stringify(trace, null, 1))
await page.screenshot({ path: 'trace-after-click.png' })
await browser.close()
