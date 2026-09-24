import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const logs = []
page.on('console', m => logs.push(`[${m.type()}] ${m.text().slice(0, 220)}`))
page.on('pageerror', e => logs.push('[pageerror] ' + String(e).slice(0, 220)))
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1500)
logs.length = 0
await page.locator('.sidebar-primary-nav .cx-nav-item', { hasText: /工具/ }).first().click()
await page.waitForTimeout(1500)
console.log('url:', page.url())
console.log(logs.length ? logs.join('\n') : '(no console output from click)')
await browser.close()
