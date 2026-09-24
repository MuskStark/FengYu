import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => { if (m.text().includes('[shell]')) console.log('SHELL-WARN:', m.text().slice(0, 250)) })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1500)
const res = await page.evaluate(async () => {
  const m = await import('/src/lib/navGuard.ts')
  const started = performance.now()
  const result = await Promise.race([
    m.checkNavigationGuard().then(v => ({ resolved: true, value: v, ms: Math.round(performance.now() - started) })),
    new Promise(r => setTimeout(() => r({ resolved: false }), 1500)),
  ])
  return result
})
console.log('guard check:', JSON.stringify(res))
await browser.close()
