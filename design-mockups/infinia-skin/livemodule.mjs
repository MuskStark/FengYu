import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1500)
const res = await page.evaluate(async () => {
  const urls = performance.getEntriesByType('resource').map(r => r.name).filter(u => u.includes('navGuard'))
  const out = { urls }
  for (const u of urls.slice(0, 3)) {
    try {
      const m = await import(u)
      const started = performance.now()
      const r = await Promise.race([
        m.checkNavigationGuard().then(v => ({ resolved: true, value: v })),
        new Promise(r => setTimeout(() => r({ resolved: false }), 1200)),
      ])
      out[u.slice(-40)] = r
    } catch (e) { out[u.slice(-40)] = 'import-err ' + String(e).slice(0, 80) }
  }
  return out
})
console.log(JSON.stringify(res, null, 1))
await browser.close()
