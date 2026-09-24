import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1500)
await page.evaluate(() => {
  window.__ev = []
  const root = document.getElementById('root')
  root.addEventListener('click', () => window.__ev.push('root-bubble'), false)
  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')].find(b => /工具/.test(b.textContent || ''))
  btn.addEventListener('click', e => window.__ev.push('btn-bubble defaultPrevented=' + e.defaultPrevented), false)
  btn.addEventListener('click', e => window.__ev.push('btn-capture'), true)
  // also patch console.warn locally to catch [shell] rejections into the trace
  const w = console.warn.bind(console)
  console.warn = (...a) => { window.__ev.push('warn ' + String(a[1] ?? a[0]).slice(0, 200)); w(...a) }
})
const btn = page.locator('.sidebar-primary-nav .cx-nav-item', { hasText: /工具/ }).first()
await btn.dispatchEvent('click')  // synthetic, bypasses hit-testing entirely
await page.waitForTimeout(1200)
const r1 = await page.evaluate(() => ({ ev: window.__ev, url: location.pathname }))
console.log('dispatchEvent:', JSON.stringify(r1))
await page.evaluate(() => { window.__ev = [] })
await btn.click()  // real trusted click
await page.waitForTimeout(1200)
const r2 = await page.evaluate(() => ({ ev: window.__ev, url: location.pathname }))
console.log('real click:', JSON.stringify(r2))
await browser.close()
